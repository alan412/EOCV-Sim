/*
 * Copyright (c) 2023 Sebastian Erives
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package io.github.deltacv.vision.external.source;

import io.github.deltacv.vision.external.util.Timestamped;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;

public abstract class VisionSourceBase implements VisionSource {

    private final Object lock = new Object();

    ArrayList<VisionSourced> sourceds = new ArrayList<>();

    SourceBaseHelperThread helperThread = new SourceBaseHelperThread(this);

    @Override
    public final boolean start(Size size) {
        boolean result = startSource(size);

        helperThread.start();

        return result;
    }

    public abstract boolean startSource(Size size);

    @Override
    public boolean attach(VisionSourced sourced) {
        synchronized (lock) {
            return sourceds.add(sourced);
        }
    }

    @Override
    public boolean remove(VisionSourced sourced) {
        synchronized (lock) {
            return sourceds.remove(sourced);
        }
    }

    @Override
    public final boolean stop() {
        if(!helperThread.isAlive() || helperThread.isInterrupted()) return false;

        helperThread.interrupt();

        return stopSource();
    }

    public abstract boolean stopSource();

    public abstract Timestamped<Mat> pullFrame();

    private Timestamped<Mat> pullFrameInternal() {
        for(VisionSourced sourced : sourceds) {
            synchronized (sourced) {
                sourced.onFrameStart();
            }
        }

        return pullFrame();
    }

    private static class SourceBaseHelperThread extends Thread {

        VisionSourceBase sourceBase;
        boolean shouldStop = false;

        Logger logger;

        public SourceBaseHelperThread(VisionSourceBase sourcedBase) {
            super("Thread-SourceBaseHelper-" + sourcedBase.getClass().getSimpleName());
            logger = LoggerFactory.getLogger(getName());

            this.sourceBase = sourcedBase;
        }

        @Override
        public void run() {
            VisionSourced[] sourceds = new VisionSourced[0];

            logger.info("starting");

            while (!isInterrupted() && !shouldStop) {
                Timestamped<Mat> frame = sourceBase.pullFrameInternal();

                synchronized (sourceBase.lock) {
                    sourceds = sourceBase.sourceds.toArray(new VisionSourced[0]);
                }

                for (VisionSourced sourced : sourceds) {
                    sourced.onNewFrame(frame.getValue(), frame.getTimestamp());
                }
            }

            for(VisionSourced sourced : sourceds) {
                sourced.stop();
            }

            logger.info("stop");
        }
    }

}

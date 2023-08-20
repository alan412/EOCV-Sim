/*
 * Copyright (c) 2021 Sebastian Erives
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

package com.github.serivesmejia.eocvsim

import com.github.serivesmejia.eocvsim.config.Config
import com.github.serivesmejia.eocvsim.config.ConfigManager
import com.github.serivesmejia.eocvsim.gui.DialogFactory
import com.github.serivesmejia.eocvsim.gui.Visualizer
import com.github.serivesmejia.eocvsim.gui.dialog.FileAlreadyExists
import com.github.serivesmejia.eocvsim.input.InputSourceManager
import com.github.serivesmejia.eocvsim.output.VideoRecordingSession
import com.github.serivesmejia.eocvsim.pipeline.PipelineManager
import com.github.serivesmejia.eocvsim.pipeline.PipelineSource
import io.github.deltacv.common.pipeline.util.PipelineStatisticsCalculator
import com.github.serivesmejia.eocvsim.tuner.TunerManager
import com.github.serivesmejia.eocvsim.util.ClasspathScan
import com.github.serivesmejia.eocvsim.util.FileFilters
import com.github.serivesmejia.eocvsim.util.SysUtil
import com.github.serivesmejia.eocvsim.util.event.EventHandler
import com.github.serivesmejia.eocvsim.util.exception.MaxActiveContextsException
import com.github.serivesmejia.eocvsim.util.exception.handling.CrashReport
import com.github.serivesmejia.eocvsim.util.exception.handling.EOCVSimUncaughtExceptionHandler
import com.github.serivesmejia.eocvsim.util.extension.plus
import com.github.serivesmejia.eocvsim.util.fps.FpsLimiter
import com.github.serivesmejia.eocvsim.util.io.EOCVSimFolder
import com.github.serivesmejia.eocvsim.util.loggerFor
import com.github.serivesmejia.eocvsim.workspace.WorkspaceManager
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import com.qualcomm.robotcore.eventloop.opmode.OpModePipelineHandler
import io.github.deltacv.vision.external.PipelineRenderHook
import nu.pattern.OpenCV
import org.opencv.core.Size
import org.openftc.easyopencv.TimestampedPipelineHandler
import java.awt.Dimension
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileFilter
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

class EOCVSim(val params: Parameters = Parameters()) {

    companion object {
        const val VERSION = Build.versionString

        const val DEFAULT_EOCV_WIDTH = 320
        const val DEFAULT_EOCV_HEIGHT = 240

        @JvmField
        val DEFAULT_EOCV_SIZE = Size(DEFAULT_EOCV_WIDTH.toDouble(), DEFAULT_EOCV_HEIGHT.toDouble())

        private var hasScanned = false
        private val classpathScan = ClasspathScan()

        val logger by loggerFor(EOCVSim::class)

        init {
            EOCVSimFolder // mkdir needed folders
        }

        private var isNativeLibLoaded = false

        fun loadOpenCvLib(alternativeNative: File? = null) {
            if (isNativeLibLoaded) return

            if (alternativeNative != null) {
                logger.info("Loading native lib from ${alternativeNative.absolutePath}...")

                try {
                    System.load(alternativeNative.absolutePath)

                    isNativeLibLoaded = true
                    logger.info("Successfully loaded the OpenCV native lib from specified path")

                    return
                } catch (ex: Throwable) {
                    logger.error("Failure loading the OpenCV native lib from specified path", ex)
                    logger.info("Retrying with loadLocally...")
                }
            }

            try {
                OpenCV.loadLocally()
                logger.info("Successfully loaded the OpenCV native lib")
            } catch (ex: Throwable) {
                logger.error("Failure loading the OpenCV native lib", ex)
                logger.error("The sim will exit now as it's impossible to continue execution without OpenCV")

                CrashReport(ex).saveCrashReport()

                exitProcess(-1)
            }

            isNativeLibLoaded = true
        }
    }

    @JvmField
    val onMainUpdate = EventHandler("OnMainUpdate")

    @JvmField
    val visualizer = Visualizer(this)

    @JvmField
    val configManager = ConfigManager()

    @JvmField
    val inputSourceManager = InputSourceManager(this)

    @JvmField
    val pipelineStatisticsCalculator = PipelineStatisticsCalculator()

    @JvmField
    val pipelineManager = PipelineManager(this, pipelineStatisticsCalculator)

    @JvmField
    val tunerManager = TunerManager(this)

    @JvmField
    val workspaceManager = WorkspaceManager(this)

    val config: Config get() = configManager.config

    val classpathScan get() = Companion.classpathScan

    var currentRecordingSession: VideoRecordingSession? = null
    val fpsLimiter = FpsLimiter(30.0)

    lateinit var eocvSimThread: Thread
        private set

    private val hexCode = Integer.toHexString(hashCode())

    private var isRestarting = false

    enum class DestroyReason {
        USER_REQUESTED, RESTART, CRASH
    }

    fun init() {
        eocvSimThread = Thread.currentThread()

        if (!EOCVSimFolder.couldLock) {
            logger.error(
                "Couldn't finally claim lock file in \"${EOCVSimFolder.absolutePath}\"! " + "Is the folder opened by another EOCV-Sim instance?"
            )

            logger.error("Unable to continue with the execution, the sim will exit now.")
            exitProcess(-1)
        } else {
            logger.info("Confirmed claiming of the lock file in ${EOCVSimFolder.absolutePath}")
        }

        DialogFactory.createSplashScreen(visualizer.onInitFinished)

        logger.info("-- Initializing EasyOpenCV Simulator v$VERSION ($hexCode) --")

        EOCVSimUncaughtExceptionHandler.register()

        //loading native lib only once in the app runtime
        loadOpenCvLib(params.opencvNativeLibrary)

        if (!hasScanned) {
            classpathScan.asyncScan()
            hasScanned = true
        }

        configManager.init() //load config

        workspaceManager.init()

        visualizer.initAsync(configManager.config.simTheme) //create gui in the EDT

        inputSourceManager.init() //loading user created input sources

        pipelineManager.init() //init pipeline manager (scan for pipelines)

        tunerManager.init() //init tunable variables manager

        //shows a warning when a pipeline gets "stuck"
        pipelineManager.onPipelineTimeout {
            visualizer.asyncPleaseWaitDialog(
                "Current pipeline took too long to ${pipelineManager.lastPipelineAction}",
                "Falling back to DefaultPipeline",
                "Close",
                Dimension(310, 150),
                true,
                true
            )
        }

        inputSourceManager.inputSourceLoader.saveInputSourcesToFile()

        visualizer.joinInit()

        pipelineManager.subscribePipelineHandler(TimestampedPipelineHandler())
        pipelineManager.subscribePipelineHandler(OpModePipelineHandler(inputSourceManager, visualizer.viewport))

        visualizer.sourceSelectorPanel.updateSourcesList() //update sources and pick first one
        visualizer.sourceSelectorPanel.sourceSelector.selectedIndex = 0
        visualizer.sourceSelectorPanel.allowSourceSwitching = true

        visualizer.pipelineOpModeSwitchablePanel.updateSelectorListsBlocking()

        visualizer.pipelineSelectorPanel.selectedIndex = 0 //update pipelines and pick first one (DefaultPipeline)
        visualizer.opModeSelectorPanel.selectedIndex = 0 //update opmodes and pick first one (DefaultPipeline)

        visualizer.pipelineOpModeSwitchablePanel.enableSwitchingBlocking()

        //post output mats from the pipeline to the visualizer viewport
        pipelineManager.pipelineOutputPosters.add(visualizer.viewport)

        // now that we have two different runnable units (OpenCvPipeline and OpMode)
        // we have to give a more special treatment to the OpenCvPipeline
        // OpModes can take care of themselves, setting up their own stuff
        // but we need to do some hand holding for OpenCvPipelines...
        pipelineManager.onPipelineChange {
            pipelineStatisticsCalculator.init()

            if(pipelineManager.currentPipeline !is OpMode && pipelineManager.currentPipeline != null) {
                visualizer.viewport.activate()
                visualizer.viewport.setRenderHook(PipelineRenderHook) // calls OpenCvPipeline#onDrawFrame on the viewport (UI) thread
            } else {
                // opmodes are on their own, lol
                visualizer.viewport.deactivate()
                visualizer.viewport.clearViewport()
            }
        }

        pipelineManager.onUpdate {
            if(pipelineManager.currentPipeline !is OpMode && pipelineManager.currentPipeline != null) {
                visualizer.viewport.notifyStatistics(
                        pipelineStatisticsCalculator.avgFps,
                        pipelineStatisticsCalculator.avgPipelineTime,
                        pipelineStatisticsCalculator.avgOverheadTime
                )
            }


            updateVisualizerTitle() // update current pipeline in title
        }

        start()
    }

    private fun start() {
        logger.info("-- Begin EOCVSim loop ($hexCode) --")

        while (!eocvSimThread.isInterrupted) {
            //run all pending requested runnables
            onMainUpdate.run()

            pipelineStatisticsCalculator.newInputFrameStart()

            inputSourceManager.update(pipelineManager.paused)
            tunerManager.update()

            try {
                pipelineManager.update(
                    if (inputSourceManager.lastMatFromSource != null && !inputSourceManager.lastMatFromSource.empty()) {
                        inputSourceManager.lastMatFromSource
                    } else null
                )
            } catch (ex: MaxActiveContextsException) { //handles when a lot of pipelines are stuck in the background
                visualizer.asyncPleaseWaitDialog(
                    "There are many pipelines stuck in processFrame running in the background",
                    "To avoid further issues, EOCV-Sim will exit now.",
                    "Ok",
                    Dimension(450, 150),
                    true,
                    true
                ).onCancel {
                    destroy(DestroyReason.CRASH) //destroy eocv sim when pressing "exit"
                }

                //print exception
                logger.error(
                    "Please note that the following exception is likely to be caused by one or more of the user pipelines",
                    ex
                )

                //block the current thread until the user closes the dialog
                try {
                    //using sleep for avoiding burning cpu cycles
                    Thread.sleep(Long.MAX_VALUE)
                } catch (ignored: InterruptedException) {
                    //reinterrupt once user closes the dialog
                    Thread.currentThread().interrupt()
                }

                break //bye bye
            } catch (ex: InterruptedException) {
                break // bye bye
            }

            //limit FPG
            fpsLimiter.maxFPS = config.pipelineMaxFps.fps.toDouble()
            try {
                fpsLimiter.sync()
            } catch (e: InterruptedException) {
                break
            }
        }

        logger.warn("Main thread interrupted ($hexCode)")

        if (isRestarting) {
            isRestarting = false
            EOCVSim(params).init()
        }
    }

    fun destroy(reason: DestroyReason) {
        logger.warn("-- Destroying current EOCVSim ($hexCode) due to $reason, it is normal to see InterruptedExceptions and other kinds of stack traces below --")

        //stop recording session if there's currently an ongoing one
        currentRecordingSession?.stopRecordingSession()
        currentRecordingSession?.discardVideo()

        logger.info("Trying to save config file...")

        inputSourceManager.currentInputSource?.close()
        workspaceManager.stopFileWatcher()
        configManager.saveToFile()
        visualizer.close()

        eocvSimThread.interrupt()

        if (reason == DestroyReason.USER_REQUESTED || reason == DestroyReason.CRASH) jvmMainThread.interrupt()
    }

    fun destroy() {
        destroy(DestroyReason.USER_REQUESTED)
    }

    fun restart() {
        logger.info("Restarting...")

        pipelineManager.captureStaticSnapshot()

        isRestarting = true
        destroy(DestroyReason.RESTART)
    }

    fun startRecordingSession() {
        if (currentRecordingSession == null) {
            currentRecordingSession = VideoRecordingSession(
                config.videoRecordingFps.fps.toDouble(), config.videoRecordingSize
            )

            currentRecordingSession!!.startRecordingSession()

            logger.info("Recording session started")

            pipelineManager.pipelineOutputPosters.add(currentRecordingSession!!.matPoster)
        }
    }

    //stopping recording session and saving file
    fun stopRecordingSession() {
        currentRecordingSession?.let { itVideo ->

            visualizer.pipelineSelectorPanel.buttonsPanel.pipelineRecordBtt.isEnabled = false

            itVideo.stopRecordingSession()
            pipelineManager.pipelineOutputPosters.remove(itVideo.matPoster)

            logger.info("Recording session stopped")

            DialogFactory.createFileChooser(
                visualizer.frame, DialogFactory.FileChooser.Mode.SAVE_FILE_SELECT, FileFilters.recordedVideoFilter
            ).addCloseListener { _: Int, file: File?, selectedFileFilter: FileFilter? ->
                onMainUpdate.doOnce {
                    if (file != null) {

                        var correctedFile = File(file.absolutePath)
                        val extension = SysUtil.getExtensionByStringHandling(file.name)

                        if (selectedFileFilter is FileNameExtensionFilter) { //if user selected an extension
                            //get selected extension
                            correctedFile = file + "." + selectedFileFilter.extensions[0]
                        } else if (extension.isPresent) {
                            if (!extension.get().equals("avi", true)) {
                                correctedFile = file + ".avi"
                            }
                        } else {
                            correctedFile = file + ".avi"
                        }

                        if (correctedFile.exists()) {
                            SwingUtilities.invokeLater {
                                if (DialogFactory.createFileAlreadyExistsDialog(this) == FileAlreadyExists.UserChoice.REPLACE) {
                                    onMainUpdate.doOnce { itVideo.saveTo(correctedFile) }
                                }
                            }
                        } else {
                            itVideo.saveTo(correctedFile)
                        }
                    } else {
                        itVideo.discardVideo()
                    }

                    currentRecordingSession = null
                    visualizer.pipelineSelectorPanel.buttonsPanel.pipelineRecordBtt.isEnabled = true
                }
            }
        }
    }

    fun isCurrentlyRecording() = currentRecordingSession?.isRecording ?: false

    private fun updateVisualizerTitle() {
        val isBuildRunning = if (pipelineManager.compiledPipelineManager.isBuildRunning) "(Building)" else ""

        val workspaceMsg = " - ${workspaceManager.workspaceFile.absolutePath} $isBuildRunning"

        val isPaused = if (pipelineManager.paused) " (Paused)" else ""
        val isRecording = if (isCurrentlyRecording()) " RECORDING" else ""

        val msg = isRecording + isPaused

        if (pipelineManager.currentPipeline == null) {
            visualizer.setTitleMessage("No pipeline$msg${workspaceMsg}")
        } else {
            visualizer.setTitleMessage("${pipelineManager.currentPipelineName}$msg${workspaceMsg}")
        }
    }

    class Parameters {
        var initialWorkspace: File? = null

        var initialPipelineName: String? = null
        var initialPipelineSource: PipelineSource? = null

        var opencvNativeLibrary: File? = null
    }

}
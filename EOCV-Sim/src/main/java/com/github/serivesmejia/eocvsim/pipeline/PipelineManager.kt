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

package com.github.serivesmejia.eocvsim.pipeline

import com.github.serivesmejia.eocvsim.EOCVSim
import com.github.serivesmejia.eocvsim.gui.DialogFactory
import com.github.serivesmejia.eocvsim.pipeline.compiler.CompiledPipelineManager
import com.github.serivesmejia.eocvsim.pipeline.handler.PipelineHandler
import com.github.serivesmejia.eocvsim.pipeline.instantiator.DefaultPipelineInstantiator
import com.github.serivesmejia.eocvsim.pipeline.instantiator.PipelineInstantiator
import com.github.serivesmejia.eocvsim.pipeline.instantiator.processor.ProcessorInstantiator
import com.github.serivesmejia.eocvsim.pipeline.util.PipelineExceptionTracker
import com.github.serivesmejia.eocvsim.pipeline.util.PipelineSnapshot
import com.github.serivesmejia.eocvsim.util.ReflectUtil
import com.github.serivesmejia.eocvsim.util.StrUtil
import com.github.serivesmejia.eocvsim.util.event.EventHandler
import com.github.serivesmejia.eocvsim.util.exception.MaxActiveContextsException
import com.github.serivesmejia.eocvsim.util.fps.FpsCounter
import com.github.serivesmejia.eocvsim.util.loggerForThis
import io.github.deltacv.common.image.MatPoster
import io.github.deltacv.common.pipeline.util.PipelineStatisticsCalculator
import kotlinx.coroutines.*
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.firstinspires.ftc.robotcore.internal.opmode.TelemetryImpl
import org.firstinspires.ftc.vision.VisionProcessor
import org.opencv.core.Mat
import org.openftc.easyopencv.OpenCvPipeline
import org.openftc.easyopencv.OpenCvViewport
import org.openftc.easyopencv.processFrameInternal
import java.lang.RuntimeException
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.util.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.roundToLong

@OptIn(DelicateCoroutinesApi::class)
class PipelineManager(var eocvSim: EOCVSim, val pipelineStatisticsCalculator: PipelineStatisticsCalculator) {

    companion object {
        const val MAX_ALLOWED_ACTIVE_PIPELINE_CONTEXTS = 5

        var staticSnapshot: PipelineSnapshot? = null
            private set
    }

    @JvmField val onUpdate          = EventHandler("OnPipelineUpdate")
    @JvmField val onPipelineChange  = EventHandler("OnPipelineChange")
    @JvmField val onPipelineTimeout = EventHandler("OnPipelineTimeout")
    @JvmField val onPause           = EventHandler("OnPipelinePause")
    @JvmField val onResume          = EventHandler("OnPipelineResume")

    val logger by loggerForThis()

    val pipelineOutputPosters = ArrayList<MatPoster>()
    val pipelineFpsCounter = FpsCounter()

    private var hasInitCurrentPipeline = false
    var lastPipelineAction = "processFrame"
        private set

    val pipelines = ArrayList<PipelineData>()

    @Volatile var currentPipeline: OpenCvPipeline? = null
        private set
    @Volatile var currentPipelineData: PipelineData? = null
        private set
    var currentTunerTarget: Any? = null
        private set
    var currentPipelineName = ""
        private set
    var currentPipelineIndex = -1
        private set
    var previousPipelineIndex = 0

    @Volatile var previousPipeline: OpenCvPipeline? = null
        private set

    val activePipelineContexts = ArrayList<ExecutorCoroutineDispatcher>()
    private var currentPipelineContext: ExecutorCoroutineDispatcher? = null

    @Volatile var currentTelemetry: Telemetry? = null
        private set

    @Volatile var paused = false
        private set
        get() {
            if (!field) pauseReason = PauseReason.NOT_PAUSED
            return field
        }

    var pauseOnImages = true

    var pauseReason = PauseReason.NOT_PAUSED
        private set
        get() {
            if (!paused) field = PauseReason.NOT_PAUSED
            return field
        }

    var latestSnapshot: PipelineSnapshot? = null
        private set

    var lastInitialSnapshot: PipelineSnapshot? = null
        private set

    var applyLatestSnapshotOnChange = false

    val snapshotFieldFilter: (Field) -> Boolean = {
        // only snapshot fields managed by the variable tuner
        // when getTunableFieldOf returns null, it means that
        // it wasn't able to find a suitable TunableField for
        // the passed Field type.
        eocvSim.tunerManager.getTunableFieldOf(it) != null
    }

    //manages and builds pipelines in runtime
    @JvmField val compiledPipelineManager = CompiledPipelineManager(this)

    private val pipelineHandlers = mutableListOf<PipelineHandler>()
    private val pipelineInstantiators = mutableMapOf<Class<*>, PipelineInstantiator>()

    //counting and tracking exceptions for logging and reporting purposes
    val pipelineExceptionTracker = PipelineExceptionTracker(this)

    private var openedPipelineOutputCount = 0

    enum class PauseReason {
        USER_REQUESTED, IMAGE_ONE_ANALYSIS, NOT_PAUSED
    }

    fun init() {
        logger.info("Initializing...")

        //add default pipeline
        addPipelineClass(DefaultPipeline::class.java)

        compiledPipelineManager.init()

        eocvSim.classpathScan.join()

        //scan for pipelines
        for(pipelineClass in eocvSim.classpathScan.scanResult.pipelineClasses) {
            addPipelineClass(pipelineClass)
        }

        logger.info("Found " + pipelines.size + " pipeline(s)")

        // add instantiator for OpenCvPipeline
        addInstantiator(OpenCvPipeline::class.java, DefaultPipelineInstantiator)
        // add instantiator for VisionProcessor (wraps a VisionProcessor around an OpenCvPipeline)
        addInstantiator(VisionProcessor::class.java, ProcessorInstantiator)

        // changing to initial pipeline
        onUpdate.doOnce {
            if(compiledPipelineManager.isBuildRunning && staticSnapshot != null)
                compiledPipelineManager.onBuildEnd.doOnce(::applyStaticSnapOrDef)
            else
                applyStaticSnapOrDef()
        }

        pipelineExceptionTracker.onNewPipelineException {
            val telemetry = currentTelemetry

            if(openedPipelineOutputCount <= 3) {
                DialogFactory.createPipelineOutput(eocvSim)
                openedPipelineOutputCount++
            }

            if(telemetry is TelemetryImpl) {
                telemetry.errItem.caption = "[/!\\]"
                telemetry.errItem.setValue("Uncaught exception thrown in\n pipeline, check Workspace -> Output.")
                telemetry.forceTelemetryTransmission()
            }
        }

        pipelineExceptionTracker.onPipelineExceptionClear {
            val telemetry = currentTelemetry

            if(telemetry is TelemetryImpl) {
                telemetry.errItem.caption = ""
                telemetry.errItem.setValue("")
                telemetry.forceTelemetryTransmission()
            }
        }

        onUpdate {
            if(currentPipeline != null) {
                for (pipelineHandler in pipelineHandlers) {
                    pipelineHandler.processFrame(eocvSim.inputSourceManager.currentInputSource)
                }
            }
        }

        onPipelineChange {
            openedPipelineOutputCount = 0

            if(currentPipeline != null) {
                for (pipelineHandler in pipelineHandlers) {
                    pipelineHandler.onChange(previousPipeline, currentPipeline!!, currentTelemetry!!)
                }
            }
        }
    }

    private fun applyStaticSnapOrDef() {
        onUpdate.doOnce {
            if(!applyStaticSnapshot()) {
                val params = eocvSim.params

                // changing to the initial pipeline, defined by the eocv sim parameters or the default pipeline
                if(params.initialPipelineName != null) {
                    changePipeline(params.initialPipelineName!!, params.initialPipelineSource ?: PipelineSource.CLASSPATH)
                } else {
                    forceChangePipeline(0)
                }
            }

            eocvSim.visualizer.pipelineOpModeSwitchablePanel.enableSwitchingBlocking()
        }
    }

    private var wasBuildRunning = false

    fun update(inputMat: Mat?) {
        val telemetry = currentTelemetry
        onUpdate.run()

        if(activePipelineContexts.size > MAX_ALLOWED_ACTIVE_PIPELINE_CONTEXTS) {
            throw MaxActiveContextsException("Current amount of active pipeline coroutine contexts (${activePipelineContexts.size}) is more than the maximum allowed. This generally means that there are multiple pipelines stuck in processFrame() running in the background, check for any lengthy operations in your pipelines.")
        }

        if(telemetry is TelemetryImpl) {
            if (compiledPipelineManager.isBuildRunning) {
                telemetry.infoItem.caption = "[>]"
                telemetry.infoItem.setValue("Building java files in workspace...")
                telemetry.forceTelemetryTransmission()
            } else {
                telemetry.infoItem.caption = ""
                telemetry.infoItem.setValue("")
            }

            if(wasBuildRunning != compiledPipelineManager.isBuildRunning) {
                telemetry.forceTelemetryTransmission()
            }

            wasBuildRunning = compiledPipelineManager.isBuildRunning
        }

        if(paused || currentPipeline == null) {
            updateExceptionTracker()
            return
        }

        lastPipelineAction = if(!hasInitCurrentPipeline) {
            "init/processFrame"
        } else {
            "processFrame"
        }

        pipelineStatisticsCalculator.newPipelineFrameStart()

        //run our pipeline in the background until it finishes or gets cancelled
        val pipelineJob = GlobalScope.launch(currentPipelineContext!!) {
            try {
                //if we have a pipeline, we run it right here, passing the input mat
                //given to us. we'll post the frame the pipeline returns as long
                //as we haven't ran out of time (the main loop will not wait it
                //forever to finish its job). if we run out of time, and if the
                //pipeline ever returns, we will not post the frame, since we
                //don't know when it was actually requested, we might even be in
                //a different pipeline at this point. we also call init if we
                //haven't done so.

                //check if we're still active (not timeouted)
                //after initialization
                if(inputMat != null) {
                    if(!hasInitCurrentPipeline) {
                        for(pipeHandler in pipelineHandlers) {
                            pipeHandler.preInit();
                        }
                    }

                    pipelineStatisticsCalculator.beforeProcessFrame()

                    val pipelineResult = currentPipeline?.processFrameInternal(inputMat)

                    pipelineStatisticsCalculator.afterProcessFrame()

                    pipelineResult?.let { outputMat ->
                        if (isActive) {
                            pipelineFpsCounter.update()

                            for (poster in pipelineOutputPosters.toTypedArray()) {
                                try {
                                    poster.post(outputMat, OpenCvViewport.FrameContext(currentPipeline, currentPipeline?.userContextForDrawHook))
                                } catch (ex: Exception) {
                                    logger.error(
                                        "Uncaught exception thrown while posting pipeline output Mat to poster",
                                        ex
                                    )
                                }
                            }
                        }
                    }

                    if(!hasInitCurrentPipeline) {
                        for(pipeHandler in pipelineHandlers) {
                            pipeHandler.init();
                        }

                        logger.info("Initialized pipeline $currentPipelineName")

                        hasInitCurrentPipeline = true
                    }
                }

                if(!isActive) {
                    activePipelineContexts.remove(this.coroutineContext)
                }

                updateExceptionTracker()
            } catch (ex: Exception) { //handling exceptions from pipelines
                if(!hasInitCurrentPipeline) {
                    pipelineExceptionTracker.addMessage("Error while initializing requested pipeline, \"$currentPipelineName\". Falling back to previous one.")
                    pipelineExceptionTracker.addMessage(
                        StrUtil.cutStringBy(
                            StrUtil.fromException(ex), "\n", 9
                        ).trim()
                    )

                    eocvSim.visualizer.pipelineSelectorPanel.selectedIndex = previousPipelineIndex
                    changePipeline(currentPipelineIndex)

                    logger.error("Error while initializing requested pipeline, $currentPipelineName", ex)
                } else {
                    updateExceptionTracker(ex)
                }
            }

            pipelineStatisticsCalculator.endFrame()
        }

        runBlocking {
            val configTimeout = eocvSim.config.pipelineTimeout

            //allow double timeout if we haven't initialized the pipeline
            val timeout = if(hasInitCurrentPipeline) {
                configTimeout.ms
            } else {
                (configTimeout.ms * 1.8).roundToLong()
            }

            try {
                //ok! this is the part in which we'll wait for the pipeline with a timeout
                withTimeout(timeout) {
                    pipelineJob.join()
                }

                activePipelineContexts.remove(currentPipelineContext)
            } catch (ex: TimeoutCancellationException) {
                //oops, pipeline ran out of time! we'll fall back
                //to default pipeline to avoid further issues.
                requestForceChangePipeline(0)
                //also call the event listeners in case
                //someone wants to do something here
                onPipelineTimeout.run()

                logger.warn( "User pipeline $currentPipelineName took too long to $lastPipelineAction (more than $timeout ms), falling back to DefaultPipeline.")
            } finally {
                //we cancel our pipeline job so that it
                //doesn't post the output mat from the
                //pipeline if it ever returns.
                pipelineJob.cancel()
            }
        }
    }

    private fun updateExceptionTracker(ex: Throwable? = null) {
        if(currentPipelineIndex < pipelines.size && currentPipeline != null) {
            pipelineExceptionTracker.update(
                pipelines[currentPipelineIndex], ex
            )
        }
    }

    fun callViewportTapped() = currentPipeline?.let { pipeline -> //run only if our pipeline is not null
        if(paused) requestSetPaused(false)

        //similar to pipeline processFrame, call the user function in the background
        //and wait for some X timeout for the user to finisih doing what it has to do.
        val viewportTappedJob = GlobalScope.launch(currentPipelineContext ?: EmptyCoroutineContext) {
            pipeline.onViewportTapped()
        }

        val configTimeoutMs = eocvSim.config.pipelineTimeout.ms

        try {
            //perform the timeout here (we'll block for a bit
            //and if it runs out of time, give up and move on)
            runBlocking {
                withTimeout(configTimeoutMs) {
                    viewportTappedJob.join()
                }
            }
        } catch(ex: TimeoutCancellationException) {
            //send a warning to the user
            logger.warn("User pipeline $currentPipelineName took too long to handle onViewportTapped (more than $configTimeoutMs ms).")
        } finally {
            //cancel the job
            viewportTappedJob.cancel()
        }
    }

    @JvmOverloads
    fun requestAddPipelineClass(C: Class<*>, source: PipelineSource = PipelineSource.CLASSPATH) {
        onUpdate.doOnce { addPipelineClass(C, source) }
    }

    fun requestAddPipelineClasses(classes: List<Class<*>>,
                                  source: PipelineSource = PipelineSource.CLASSPATH,
                                  refreshGui: Boolean = false) {
        onUpdate.doOnce {
            for(clazz in classes) {
                addPipelineClass(clazz, source)
            }
            if(refreshGui) refreshGuiPipelineList()
        }
    }

    fun subscribePipelineHandler(handler: PipelineHandler) {
        pipelineHandlers.add(handler)
    }

    fun addInstantiator(instantiatorFor: Class<*>, instantiator: PipelineInstantiator) {
        pipelineInstantiators.put(instantiatorFor, instantiator)
    }

    fun getInstantiatorFor(clazz: Class<*>): PipelineInstantiator? {
        for((instantiatorFor, instantiator) in pipelineInstantiators) {
            if(ReflectUtil.hasSuperclass(clazz, instantiatorFor)) {
                return instantiator
            }
        }

        return null
    }

    @Suppress("UNCHECKED_CAST")
    @JvmOverloads fun addPipelineClass(C: Class<*>, source: PipelineSource = PipelineSource.CLASSPATH) {
        try {
            pipelines.add(PipelineData(source, C))
        } catch (ex: Exception) {
            logger.warn("Error while adding pipeline class", ex)
            updateExceptionTracker(ex)
        }
    }

    @JvmOverloads fun removeAllPipelinesFrom(source: PipelineSource,
                                             refreshGuiPipelineList: Boolean = true,
                                             changeToDefaultIfRemoved: Boolean = true) {
        for(pipeline in pipelines.toTypedArray()) {
            if(pipeline.source == source) {
                pipelines.remove(pipeline)

                if(currentPipeline != null && currentPipeline!!::class.java == pipeline.clazz) {
                    if(changeToDefaultIfRemoved)
                        requestChangePipeline(0) //change to default pipeline if the current pipeline was deleted
                }
            }
        }

        if(refreshGuiPipelineList) refreshGuiPipelineList()
    }

    @JvmOverloads
    fun requestRemoveAllPipelinesFrom(source: PipelineSource,
                                      refreshGuiPipelineList: Boolean = true,
                                      changeToDefaultIfRemoved: Boolean = true) {
        onUpdate.doOnce {
            removeAllPipelinesFrom(source, refreshGuiPipelineList, changeToDefaultIfRemoved)
        }
    }

    fun changePipeline(name: String, source: PipelineSource) {
        for((i, data) in pipelines.withIndex()) {
            if(data.clazz.simpleName.equals(name, true) && data.source == source) {
                changePipeline(i)
                return
            }

            if(data.clazz.name.equals(name, true) && data.source == source) {
                changePipeline(i)
                return
            }
        }

        logger.warn("Pipeline class with name $name and source $source couldn't be found")
    }

    fun requestChangePipeline(name: String, source: PipelineSource) {
        eocvSim.onMainUpdate.doOnce {
            changePipeline(name, source)
        }
    }

    /**
     * Changes to the requested pipeline, no matter
     * if we're currently on the same pipeline or not
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun forceChangePipeline(index: Int?,
                            applyLatestSnapshot: Boolean = applyLatestSnapshotOnChange,
                            applyStaticSnapshot: Boolean = false) {
        if(index == null) {
            previousPipelineIndex = currentPipelineIndex

            currentPipeline = null
            currentPipelineName = ""
            currentPipelineContext = null
            currentPipelineData = null
            currentPipelineIndex = -1

            onPipelineChange.run()
            logger.info("Set to null pipeline")

            return
        }

        captureSnapshot()

        var nextPipeline: OpenCvPipeline?
        var nextTelemetry: Telemetry?
        val pipelineClass = pipelines[index].clazz

        logger.info("Changing to pipeline ${pipelineClass.name}")

        debugLogCalled("forceChangePipeline")

        val instantiator = getInstantiatorFor(pipelineClass)

        try {
            nextTelemetry = TelemetryImpl().apply {
                // send telemetry updates to the ui
                addTransmissionReceiver(eocvSim.visualizer.telemetryPanel)
            }

            nextPipeline = instantiator?.instantiate(pipelineClass, nextTelemetry)
                ?: throw RuntimeException("No instantiator found for pipeline class ${pipelineClass.name}")

            logger.info("Instantiated pipeline class ${pipelineClass.name}")
        } catch (ex: NoSuchMethodException) {
            pipelineExceptionTracker.addMessage("Error while instantiating requested pipeline, \"${pipelineClass.simpleName}\". Falling back to previous one.")
            pipelineExceptionTracker.addMessage("Make sure your pipeline implements a public constructor with no parameters or a Telemetry parameter.")

            eocvSim.visualizer.pipelineSelectorPanel.selectedIndex = currentPipelineIndex

            logger.error("Error while instantiating requested pipeline, ${pipelineClass.simpleName} (usable constructor missing)", ex)
            return
        } catch (ex: Exception) {
            pipelineExceptionTracker.addMessage("Error while instantiating requested pipeline, \"${pipelineClass.simpleName}\". Falling back to previous one.")
            updateExceptionTracker(ex)

            logger.error("Error while instantiating requested pipeline, ${pipelineClass.simpleName} (unknown issue)", ex)

            eocvSim.visualizer.pipelineSelectorPanel.selectedIndex = currentPipelineIndex

            return
        }

        previousPipelineIndex = currentPipelineIndex
        previousPipeline = currentPipeline

        currentPipeline      = nextPipeline
        currentPipelineData  = pipelines[index]
        currentTelemetry     = nextTelemetry
        currentPipelineIndex = index
        currentPipelineName  = currentPipeline!!.javaClass.simpleName
        currentTunerTarget   = instantiator.variableTunerTargetObject(currentPipeline!!)

        currentTelemetry?.update() // clear telemetry

        val snap = PipelineSnapshot(currentPipeline!!, snapshotFieldFilter)

        lastInitialSnapshot = if(applyLatestSnapshot) {
            applyLatestSnapshot()
            snap
        } else snap

        if(applyStaticSnapshot) staticSnapshot?.transferTo(currentPipeline!!)

        hasInitCurrentPipeline = false

        currentPipelineContext?.close()
        currentPipelineContext = newSingleThreadContext("Pipeline-$currentPipelineName")

        activePipelineContexts.add(currentPipelineContext!!)

        eocvSim.visualizer.pipelineSelectorPanel.selectedIndex = currentPipelineIndex

        setPaused(false)

        //if pause on images option is turned on by user
        if (eocvSim.configManager.config.pauseOnImages && pauseOnImages) {
            //pause next frame if current selected input source is an image
            eocvSim.inputSourceManager.pauseIfImageTwoFrames()
        }

        onPipelineChange.run()
    }

    /**
     * Change to the requested pipeline only if we're
     * not in the requested pipeline right now.
     */
    fun changePipeline(index: Int?) {
        if (index == currentPipelineIndex) return
        forceChangePipeline(index)
    }

    fun requestChangePipeline(index: Int?) {
        onUpdate.doOnce {
            changePipeline(index)
        }
    }

    fun requestForceChangePipeline(index: Int) {
        debugLogCalled("requestForceChangePipeline")

        onUpdate.doOnce { forceChangePipeline(index) }
    }

    fun applyLatestSnapshot() {
        if(currentPipeline != null && latestSnapshot != null) {
            latestSnapshot!!.transferTo(currentPipeline!!, lastInitialSnapshot)
        }
    }

    fun captureSnapshot() {
        if(currentPipeline != null) {
            latestSnapshot = PipelineSnapshot(currentPipeline!!, snapshotFieldFilter)
        }
    }

    fun captureStaticSnapshot() {
        if(currentPipeline != null) {
            staticSnapshot = PipelineSnapshot(currentPipeline!!, snapshotFieldFilter)
        }
    }

    fun applyStaticSnapshot(): Boolean {
        staticSnapshot?.let { snap ->
            onUpdate.doOnce {
                val index = getIndexOf(snap.pipelineClass)

                if(index != null) {
                    forceChangePipeline(index, applyStaticSnapshot = true)
                    staticSnapshot = null
               }
            }
            return@applyStaticSnapshot true
        }

        staticSnapshot = null
        return false
    }

    fun getIndexOf(pipeline: OpenCvPipeline, source: PipelineSource = PipelineSource.CLASSPATH) =
        getIndexOf(pipeline::class.java, source)

    fun getIndexOf(pipelineClass: Class<out OpenCvPipeline>, source: PipelineSource = PipelineSource.CLASSPATH): Int? {
        for((i, pipelineData) in pipelines.withIndex()) {
            if(pipelineData.clazz.name == pipelineClass.name && pipelineData.source == source) {
                return i
            }
        }

        return null
    }

    fun getPipelinesFrom(source: PipelineSource): Array<PipelineData> {
        val pipelinesData = arrayListOf<PipelineData>()

        for(pipeline in pipelines) {
            if(pipeline.source == source)
                pipelinesData.add(pipeline)
        }

        return pipelinesData.toTypedArray()
    }

    fun runThenPause() {
        setPaused(false)
        eocvSim.onMainUpdate.doOnce { setPaused(true) }
    }

    fun setPaused(paused: Boolean, pauseReason: PauseReason = PauseReason.USER_REQUESTED) {
        this.paused = paused

        if (this.paused) {
            this.pauseReason = pauseReason
            onPause.run()
        } else {
            this.pauseReason = PauseReason.NOT_PAUSED
            onResume.run()
        }

        eocvSim.visualizer.pipelineSelectorPanel.buttonsPanel.pipelinePauseBtt.isSelected = paused
    }

    fun togglePause() = setPaused(!paused)

    @JvmOverloads
    fun requestSetPaused(paused: Boolean, pauseReason: PauseReason = PauseReason.USER_REQUESTED) {
        eocvSim.onMainUpdate.doOnce { setPaused(paused, pauseReason) }
    }

    fun refreshGuiPipelineList() {
        eocvSim.visualizer.pipelineOpModeSwitchablePanel.updateSelectorLists()
    }

    fun reloadPipelineByName() {
        for((i, pipeline) in pipelines.withIndex()) {
            if(pipeline.clazz.name == currentPipelineData?.clazz?.name && pipeline.source == currentPipelineData?.source) {
                forceChangePipeline(i, true)
                return
            }
        }

        forceChangePipeline(0) // default pipeline
    }

    private fun debugLogCalled(name: String) {
        val builder = StringBuilder()
        for (s in Thread.currentThread().stackTrace) {
            builder.appendLine(s.toString())
        }

        logger.debug("$name called in: {}", builder.toString().trim())
    }

}

enum class PipelineTimeout(val ms: Long, val coolName: String) {
    LOW(1000, "Low (1 sec)"),
    MEDIUM(4100, "Medium (4.1 secs)"),
    HIGH(8200, "High (8.2 secs)"),
    HIGHEST(12400, "Highest (12.4 secs)");

    companion object {
        @JvmStatic
        fun fromCoolName(coolName: String): PipelineTimeout? {
            for(timeout in values()) {
                if(timeout.coolName == coolName)
                    return timeout
            }
            return null
        }
    }
}

enum class PipelineFps(val fps: Int, val coolName: String) {
    LOW(10, "Low (10 FPS)"),
    MEDIUM(30, "Medium (30 FPS)"),
    HIGH(60, "High (60 FPS)"),
    HIGHEST(100, "Highest (100 FPS)");

    companion object {
        @JvmStatic
        fun fromCoolName(coolName: String): PipelineFps? {
            for(fps in values()) {
                if(fps.coolName == coolName)
                    return fps
            }
            return null
        }
    }
}

data class PipelineData(val source: PipelineSource, val clazz: Class<*>)

enum class PipelineSource { CLASSPATH, COMPILED_ON_RUNTIME }

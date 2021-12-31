package io.github.deltacv.eocvsim.pipeline.py

import io.github.deltacv.eocvsim.virtualreflect.py.PyWrapper
import org.firstinspires.ftc.robotcore.external.Telemetry
import org.opencv.core.Mat
import org.openftc.easyopencv.OpenCvPipeline
import org.python.core.Py
import org.python.core.PyFunction
import org.python.core.PyObject
import org.python.util.PythonInterpreter

class PythonPipeline(
    override val name: String,
    val source: String,
    val telemetry: Telemetry
) : PyWrapper, OpenCvPipeline() {

    var initFunction: PyFunction? = null
        private set
    var processFrameFunction: PyFunction
        private set
    var onViewportTappedFunction: PyFunction? = null
        private set

    private lateinit var matPyObject: PyObject

    override val interpreter = PythonInterpreter().apply {
        set("telemetry", Py.java2py(telemetry))
        exec(source)
    }

    init {
        val initFunc = interpreter.get("init")
        if(initFunc is PyFunction) {
            initFunction = initFunc
        }

        val processFrameFunc = interpreter.get("processFrame")
        if(processFrameFunc is PyFunction) {
            processFrameFunction = processFrameFunc
        } else throw NoSuchMethodException("processFrame function was not found in the python script")

        val onViewportTappedFunc = interpreter.get("onViewportTapped")
        if(onViewportTappedFunc is PyFunction) {
            onViewportTappedFunction = onViewportTappedFunc
        }
    }

    override fun init(mat: Mat) {
        matPyObject = Py.java2py(mat)
        initFunction?.__call__(matPyObject)
    }

    override fun processFrame(input: Mat): Mat {
        return processFrameFunction.__call__(matPyObject).__tojava__(Mat::class.java) as Mat
    }

    override fun onViewportTapped() {
        onViewportTappedFunction?.__call__()
    }

}
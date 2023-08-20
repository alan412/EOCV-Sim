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

package com.github.serivesmejia.eocvsim.util

import com.github.serivesmejia.eocvsim.tuner.TunableField
import com.github.serivesmejia.eocvsim.tuner.TunableFieldAcceptor
import com.github.serivesmejia.eocvsim.tuner.scanner.RegisterTunableField
import com.qualcomm.robotcore.eventloop.opmode.Disabled
import com.qualcomm.robotcore.util.ElapsedTime
import io.github.classgraph.ClassGraph
import kotlinx.coroutines.*
import org.firstinspires.ftc.vision.VisionProcessor
import org.openftc.easyopencv.OpenCvPipeline

class ClasspathScan {

    companion object {
        val ignoredPackages = arrayOf(
            "java",
            "kotlin",
            "org.opencv",
            "imgui",
            "io.github.classgraph",
            "io.github.deltacv",
            "com.github.serivesmejia.eocvsim.pipeline",
            "org.lwjgl",
            "org.apache",
            "org.codehaus",
            "com.google"
        )
    }

    val logger by loggerForThis()

    lateinit var scanResult: ScanResult
        private set

    private lateinit var scanResultJob: Job

    @Suppress("UNCHECKED_CAST")
    fun scan(jarFile: String? = null, classLoader: ClassLoader? = null, addProcessorsAsPipelines: Boolean = true): ScanResult {
        val timer = ElapsedTime()
        val classGraph = ClassGraph()
            .enableClassInfo()
            //.verbose()
            .enableAnnotationInfo()
            .rejectPackages(*ignoredPackages)

        if(jarFile != null) {
            classGraph.overrideClasspath("$jarFile!/")
            logger.info("Starting to scan for classes in $jarFile...")
        } else {
            logger.info("Starting to scan classpath...")
        }

        val scanResult = classGraph.scan()

        logger.info("ClassGraph finished scanning (took ${timer.seconds()}s)")
        
        val tunableFieldClassesInfo = scanResult.getClassesWithAnnotation(RegisterTunableField::class.java.name)

        val pipelineClasses = mutableListOf<Class<*>>()

        // i...don't even know how to name this, sorry, future readers
        // but classgraph for some reason does not have a recursive search for subclasses...
        fun searchPipelinesOfSuperclass(superclass: String) {
            val superclassClazz = if(classLoader != null) {
                classLoader.loadClass(superclass)
            } else Class.forName(superclass)

            val pipelineClassesInfo = if(superclassClazz.isInterface)
                scanResult.getClassesImplementing(superclass)
                else scanResult.getSubclasses(superclass)

            for(pipelineClassInfo in pipelineClassesInfo) {
                for(pipelineSubclassInfo in pipelineClassInfo.subclasses) {
                    searchPipelinesOfSuperclass(pipelineSubclassInfo.name) // naming is my passion
                }

                if(pipelineClassInfo.isAbstract || pipelineClassInfo.isInterface) {
                    continue // nope'd outta here
                }

                val clazz = if(classLoader != null) {
                    classLoader.loadClass(pipelineClassInfo.name)
                } else Class.forName(pipelineClassInfo.name)

                if(!pipelineClasses.contains(clazz) && ReflectUtil.hasSuperclass(clazz, superclassClazz)) {
                    if(clazz.isAnnotationPresent(Disabled::class.java)) {
                        logger.info("Found @Disabled pipeline ${clazz.typeName}")
                    } else {
                        logger.info("Found pipeline ${clazz.typeName}")
                        pipelineClasses.add(clazz)
                    }
                }
            }
        }

        // start recursive hell
        searchPipelinesOfSuperclass(OpenCvPipeline::class.java.name)

        if(addProcessorsAsPipelines) {
            logger.info("Searching for VisionProcessors...")
            searchPipelinesOfSuperclass(VisionProcessor::class.java.name)
        }

        logger.info("Found ${pipelineClasses.size} pipelines")

        val tunableFieldClasses = mutableListOf<Class<out TunableField<*>>>()
        val tunableFieldAcceptorClasses = mutableMapOf<Class<out TunableField<*>>, Class<out TunableFieldAcceptor>>()

        for(tunableFieldClassInfo in tunableFieldClassesInfo) {
            val clazz = if(classLoader != null) {
                classLoader.loadClass(tunableFieldClassInfo.name)
            } else Class.forName(tunableFieldClassInfo.name)

            if(ReflectUtil.hasSuperclass(clazz, TunableField::class.java)) {
                val tunableFieldClass = clazz as Class<out TunableField<*>>

                tunableFieldClasses.add(tunableFieldClass)
                logger.trace("Found tunable field ${clazz.typeName}")

                for(subclass in clazz.declaredClasses) {
                    if(ReflectUtil.hasSuperclass(subclass, TunableFieldAcceptor::class.java)) {
                        tunableFieldAcceptorClasses[tunableFieldClass] = subclass as Class<out TunableFieldAcceptor>
                        logger.trace("Found acceptor for this tunable field, ${clazz.typeName}")
                        break
                    }
                }
            }
        }

        logger.trace("Found ${tunableFieldClasses.size} tunable fields and ${tunableFieldAcceptorClasses.size} acceptors")

        logger.info("Finished scanning (took ${timer.seconds()}s)")

        this.scanResult = ScanResult(
            pipelineClasses.toTypedArray(),
            tunableFieldClasses.toTypedArray(),
            tunableFieldAcceptorClasses.toMap()
        )

        return this.scanResult
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun asyncScan() {
        scanResultJob = GlobalScope.launch(Dispatchers.IO) {
            scan()
        }
    }

    fun join() = runBlocking {
        scanResultJob.join()
    }

}

data class ScanResult(
    val pipelineClasses: Array<Class<*>>,
    val tunableFieldClasses: Array<Class<out TunableField<*>>>,
    val tunableFieldAcceptorClasses: Map<Class<out TunableField<*>>, Class<out TunableFieldAcceptor>>
)
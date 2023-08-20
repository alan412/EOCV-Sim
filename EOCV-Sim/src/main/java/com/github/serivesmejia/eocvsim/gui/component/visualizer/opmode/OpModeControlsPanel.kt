package com.github.serivesmejia.eocvsim.gui.component.visualizer.opmode

import com.github.serivesmejia.eocvsim.EOCVSim
import com.github.serivesmejia.eocvsim.gui.EOCVSimIconLibrary
import com.github.serivesmejia.eocvsim.pipeline.PipelineManager
import com.qualcomm.robotcore.eventloop.opmode.OpMode
import io.github.deltacv.vision.internal.opmode.OpModeNotification
import io.github.deltacv.vision.internal.opmode.OpModeState
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JButton
import javax.swing.SwingUtilities

class OpModeControlsPanel(val eocvSim: EOCVSim) : JPanel() {

    val controlButton = JButton()

    var currentOpMode: OpMode? = null
        private set

    private var currentManagerIndex: Int? = null
    private var upcomingIndex: Int? = null

    var isActive = false

    init {
        layout = BorderLayout()

        add(controlButton, BorderLayout.CENTER)

        controlButton.isEnabled = false
        controlButton.icon = EOCVSimIconLibrary.icoFlag

        controlButton.addActionListener {
            eocvSim.pipelineManager.onUpdate.doOnce {
                if(eocvSim.pipelineManager.currentPipeline !is OpMode) return@doOnce

                eocvSim.pipelineManager.setPaused(false, PipelineManager.PauseReason.NOT_PAUSED)

                val opMode = eocvSim.pipelineManager.currentPipeline as OpMode
                val state = opMode.notifier.state

                opMode.notifier.notify(when(state) {
                    OpModeState.SELECTED -> OpModeNotification.INIT
                    OpModeState.INIT -> OpModeNotification.START
                    OpModeState.START -> OpModeNotification.STOP
                    else -> OpModeNotification.NOTHING
                })
            }
        }
    }

    fun stopCurrentOpMode() {
        if(eocvSim.pipelineManager.currentPipeline != currentOpMode || currentOpMode == null) return
        currentOpMode!!.notifier.notify(OpModeNotification.STOP)
    }

    private fun notifySelected() {
        if(!isActive) return

        if(eocvSim.pipelineManager.currentPipeline !is OpMode) return
        val opMode = eocvSim.pipelineManager.currentPipeline as OpMode
        val opModeIndex = currentManagerIndex!!

        opMode.notifier.onStateChange {
            val state = opMode.notifier.state

            SwingUtilities.invokeLater {
                updateButtonState(state)
            }

            if(state == OpModeState.STOPPED) {
                if(isActive && opModeIndex == upcomingIndex) {
                    opModeSelected(currentManagerIndex!!)
                }

                it.removeThis()
            }
        }

        opMode.notifier.notify(OpModeState.SELECTED)

        currentOpMode = opMode
    }

    private fun updateButtonState(state: OpModeState) {
        when(state) {
            OpModeState.SELECTED -> controlButton.isEnabled = true
            OpModeState.INIT -> controlButton.icon = EOCVSimIconLibrary.icoPlay
            OpModeState.START -> controlButton.icon = EOCVSimIconLibrary.icoStop
            OpModeState.STOP -> {
                controlButton.isEnabled = false
            }
            OpModeState.STOPPED -> {
                controlButton.isEnabled = true

                controlButton.icon = EOCVSimIconLibrary.icoFlag
            }
        }
    }

    fun opModeSelected(managerIndex: Int, forceChangePipeline: Boolean = true) {
        eocvSim.pipelineManager.requestSetPaused(false)

        if(forceChangePipeline) {
            eocvSim.pipelineManager.requestForceChangePipeline(managerIndex)
        }

        upcomingIndex = managerIndex

        eocvSim.pipelineManager.onUpdate.doOnce {
            currentManagerIndex = managerIndex
            notifySelected()
        }
    }

    fun reset() {
        controlButton.isEnabled = false
        controlButton.icon = EOCVSimIconLibrary.icoFlag

        currentOpMode?.requestOpModeStop()
    }

}
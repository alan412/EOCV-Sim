package io.github.deltacv.easyvision.attribute.vision

import imgui.ImGui
import io.github.deltacv.easyvision.attribute.Attribute
import io.github.deltacv.easyvision.attribute.AttributeMode
import io.github.deltacv.easyvision.attribute.TypedAttribute
import io.github.deltacv.easyvision.attribute.math.DoubleAttribute
import io.github.deltacv.easyvision.attribute.misc.ListAttribute
import io.github.deltacv.easyvision.codegen.CodeGen
import io.github.deltacv.easyvision.codegen.GenValue
import io.github.deltacv.easyvision.node.vision.Colors

class ScalarAttribute(
    mode: AttributeMode,
    color: Colors,
    variableName: String? = null
) : ListAttribute(mode, DoubleAttribute, variableName, color.channels) {

    var color = color
        set(value) {
            fixedLength = value.channels
            field = value
        }

    override var typeName = "(Scalar)"

    override fun drawAttributeText(index: Int, attrib: Attribute) {
        if(index < color.channelNames.size) {
            val name = color.channelNames[index]
            val elementName = name + if(name.length == 1) " " else ""

            if(attrib is TypedAttribute) {
                attrib.drawDescriptiveText = false
                attrib.inputSameLine = true
            }

            ImGui.text(elementName)
            ImGui.sameLine()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun value(current: CodeGen.Current): GenValue.Scalar {
        return if(isInput) {
            if(hasLink) {
                val linkedAttrib = linkedAttribute()

                raiseAssert(
                    linkedAttrib != null,
                    "Scalar attribute must have another attribute attached"
                )

                raiseAssert(
                    linkedAttrib is ScalarAttribute,
                    "Attribute attached is not a Scalar"
                )

                linkedAttrib!!.value(current) as GenValue.Scalar
            } else {
                val values = (super.value(current) as GenValue.List).elements
                val ZERO = GenValue.Double(0.0)

                GenValue.Scalar(
                    (values.getOr(0, ZERO) as GenValue.Double).value,
                    (values.getOr(1, ZERO) as GenValue.Double).value,
                    (values.getOr(2, ZERO) as GenValue.Double).value,
                    (values.getOr(3, ZERO) as GenValue.Double).value
                )
            }
        } else {
            val value = getOutputValue(current)
            raiseAssert(value is GenValue.Scalar, "Value returned from the node is not a scalar")

            return value as GenValue.Scalar
        }
    }

    override fun value(current: CodeGen.Current): GenValue.Scalar {
        val values = (super.value(current) as GenValue.List).elements
        val ZERO = GenValue.Double(0.0)

        val value = GenValue.Scalar(
            (values.getOr(0, ZERO) as GenValue.Double).value,
            (values.getOr(1, ZERO) as GenValue.Double).value,
            (values.getOr(2, ZERO) as GenValue.Double).value,
            (values.getOr(3, ZERO) as GenValue.Double).value
        )

        return value(
            current, "a Points", value
        ) { it is GenValue.Scalar }
    }

}
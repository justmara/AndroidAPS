package app.aaps.plugins.aps.extensions

import org.apache.commons.lang3.ClassUtils
import kotlin.reflect.full.declaredMemberProperties

private fun String.bold(): String = "<b>$this</b>"
private const val BR = "<br>"

/**
 * Reflection-based HTML dump of a data class' primitive / StringBuilder properties.
 * Shared by the AUTO ISF tab ([app.aaps.plugins.aps.OpenAPSFragment]) and the Boost overview
 * AutoISF "Result" popup so the rendering stays identical. Wrap the result in
 * [app.aaps.core.utils.HtmlHelper.fromHtml] to get a `Spanned`.
 */
fun Any.dataClassToHtmlString(): String =
    StringBuilder().also { sb ->
        this::class.declaredMemberProperties.forEach { property ->
            property.call(this)?.let { value ->
                if (ClassUtils.isPrimitiveOrWrapper(value::class.java)) sb.append(property.name.bold(), ": ", value, BR)
                if (value is StringBuilder) sb.append(property.name.bold(), ": ", value.toString(), BR)
            }
        }
    }.toString()

/** As [dataClassToHtmlString] but restricted to the named [properties], in the given order. */
fun Any.dataClassToHtmlString(properties: List<String>): String =
    StringBuilder().also { sb ->
        properties.forEach { property ->
            this::class.declaredMemberProperties
                .firstOrNull { it.name == property }?.call(this)
                ?.let { value ->
                    if (ClassUtils.isPrimitiveOrWrapper(value::class.java)) sb.append(property.bold(), ": ", value, BR)
                    if (value is StringBuilder) sb.append(property.bold(), ": ", value.toString(), BR)
                }
        }
    }.toString()

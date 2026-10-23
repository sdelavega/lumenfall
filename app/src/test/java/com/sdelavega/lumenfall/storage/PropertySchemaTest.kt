// Copyright (C) 2026 Stephen de la Vega
// SPDX-License-Identifier: GPL-3.0-or-later
package com.sdelavega.lumenfall.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PropertySchemaTest {
    private fun envelope(vararg props: String) =
        """{"ok":true,"properties":[${props.joinToString(",")}]}"""

    private fun prop(key: String, type: String, default: String, raw: String = "{}") =
        """{"key":"$key","display_name":"Label $key","property_type":"$type","default_value":$default,"raw":$raw}"""

    private fun parse(vararg props: String): List<PropertySchema> {
        val r = PropertySchemaParser.parseEnvelope(envelope(*props))
        assertTrue(r.toString(), r is PropertyParseResult.Ok)
        return (r as PropertyParseResult.Ok).properties
    }

    @Test fun sliderParsesRange() {
        val s = parse(prop("a", "slider", "5", """{"min":1,"max":10,"step":0.5}"""))
        assertEquals(PropertySchema.Slider("a", "Label a", 1.0, 10.0, 0.5, 5.0), s.single())
    }

    @Test fun sliderDefaultsAndSwap() {
        val s = parse(
            prop("a", "slider", "5", "{}"),
            prop("b", "slider", "3", """{"min":10,"max":2,"step":0}""")
        )
        assertEquals(PropertySchema.Slider("a", "Label a", 0.0, 100.0, 1.0, 5.0), s[0])
        assertEquals(PropertySchema.Slider("b", "Label b", 2.0, 10.0, 1.0, 3.0), s[1])
    }

    @Test fun colorAndCheckbox() {
        val s = parse(prop("c", "color", "\"#ff0000\""), prop("k", "checkbox", "true"))
        assertEquals(PropertySchema.Color("c", "Label c", "#ff0000"), s[0])
        assertEquals(PropertySchema.Checkbox("k", "Label k", true), s[1])
    }

    @Test fun dropdownItemsAndDefaultIndex() {
        val s = parse(
            prop("d", "dropdown", "1", """{"items":["x","y","z"]}"""),
            prop("e", "dropdown", "-1", """{"items":["x","y"]}""")
        )
        assertEquals(PropertySchema.Dropdown("d", "Label d", listOf("x", "y", "z"), 1), s[0])
        assertEquals(PropertySchema.Dropdown("e", "Label e", listOf("x", "y"), 0), s[1])
    }

    @Test fun folderDropdownExtensionsFromFilter() {
        val s = parse(
            prop("f", "folderdropdown", "\"a.jpg\"", """{"folder":"imgs","filter":"*.JPG|*.png|*.*|x"}""")
        )
        assertEquals(
            PropertySchema.FolderDropdown("f", "Label f", "imgs", setOf("jpg", "png"), "a.jpg"),
            s.single()
        )
    }

    @Test fun textButtonLabel() {
        val s = parse(
            prop("t", "textbox", "\"hi\""),
            prop("b", "button", "null"),
            prop("l", "label", "null")
        )
        assertEquals(PropertySchema.Text("t", "Label t", "hi"), s[0])
        assertEquals(PropertySchema.Button("b", "Label b"), s[1])
        assertEquals(PropertySchema.Label("l", "Label l"), s[2])
    }

    @Test fun failedEnvelopeCarriesReason() {
        assertEquals(
            PropertyParseResult.Failed("invalid JSON: x"),
            PropertySchemaParser.parseEnvelope("""{"ok":false,"error":"invalid JSON: x"}""")
        )
        assertEquals(
            PropertyParseResult.Failed("invalid properties envelope"),
            PropertySchemaParser.parseEnvelope("not json")
        )
    }

    @Test fun malformedEntriesDegrade() {
        val s = parse(
            prop("u", "weird", "\"str\""),
            prop("v", "weird", "null"),
            prop("d", "dropdown", "\"s\"", """{"items":"nope"}"""),
            prop("n", "dropdown", "2", """{"items":"nope"}"""),
            """{"display_name":"no key","property_type":"label","default_value":null,"raw":{}}""",
            prop("ok", "checkbox", "false")
        )
        assertEquals(
            listOf(
                PropertySchema.Text("u", "Label u", "str"),
                PropertySchema.Label("v", "Label v"),
                PropertySchema.Text("d", "Label d", "s"),
                PropertySchema.Label("n", "Label n"),
                PropertySchema.Checkbox("ok", "Label ok", false)
            ),
            s
        )
    }

    @Test fun dropdownStringDefaultResolvesToIndex() {
        val s = parse(
            prop("d", "dropdown", "\"High\"", """{"items":["Low","High"]}"""),
            prop("e", "dropdown", "\"Nope\"", """{"items":["Low","High"]}""")
        )
        assertEquals(PropertySchema.Dropdown("d", "Label d", listOf("Low", "High"), 1), s[0])
        assertEquals(PropertySchema.Dropdown("e", "Label e", listOf("Low", "High"), 0), s[1])
    }

    @Test fun dropdownOutOfRangeMatchesLegacyBehaviour() {
        val s = parse(
            prop("hi", "dropdown", "9", """{"items":["x","y","z"]}"""),
            prop("lo", "dropdown", "-3", """{"items":["x","y","z"]}"""),
            prop("empty", "dropdown", "4", """{"items":[]}""")
        )
        assertEquals(2, (s[0] as PropertySchema.Dropdown).defaultIndex)
        assertEquals(0, (s[1] as PropertySchema.Dropdown).defaultIndex)
        assertEquals(0, (s[2] as PropertySchema.Dropdown).defaultIndex)
    }

    @Test fun typeMatchingIsCaseInsensitive() {
        val s = parse(
            prop("f", "FolderDropdown", "\"a.png\"", """{"folder":"i","filter":"*.png"}"""),
            prop("s", "Slider", "1", "{}")
        )
        assertTrue(s[0] is PropertySchema.FolderDropdown)
        assertTrue(s[1] is PropertySchema.Slider)
    }
}

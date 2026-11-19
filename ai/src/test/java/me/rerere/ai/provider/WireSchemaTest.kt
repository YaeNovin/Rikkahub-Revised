package me.rerere.ai.provider

import kotlinx.serialization.json.*
import me.rerere.ai.core.Tool
import org.junit.Assert.*
import org.junit.Test

class WireSchemaTest {
    @Test fun `nullable empty objects are also closed in strict output`() {
        val schema = parseOutputSchema("""{"type":["object","null"]}""").closedOutputSchema(requireAll = true)
        assertEquals(JsonPrimitive(false), schema["additionalProperties"])
        assertEquals(buildJsonObject {}, schema["properties"])
        assertEquals(buildJsonArray {}, schema["required"])
    }
    @Test fun `zero argument tools encode an object rather than null`() {
        val schema = Tool("clock", "get time", execute = { emptyList() }).wireParameters()
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        assertEquals(JsonObject(emptyMap()), schema["properties"])
        assertEquals(JsonArray(emptyList()), schema["required"])
    }
    @Test fun `OpenAI strict output closes nested objects and allows optional values to be null`() {
        val schema = parseOutputSchema("""{"type":"object","properties":{"items":{"type":"string"},"required":{"type":"object","properties":{"x":{"type":"integer"}},"required":["x"]}},"required":["items"]}""")
        val wire = schema.closedOutputSchema(requireAll = true)
        assertEquals(false, wire["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("items", "required"), wire["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("string", wire["properties"]!!.jsonObject["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val optional = wire["properties"]!!.jsonObject["required"]!!.jsonObject["anyOf"]!!.jsonArray
        assertEquals("null", optional[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(false, optional[0].jsonObject["additionalProperties"]!!.jsonPrimitive.boolean)
        assertFalse(schema.containsKey("additionalProperties"))
    }
    @Test fun `Claude output keeps optional field semantics`() {
        val schema = parseOutputSchema("""{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}},"required":["a"]}""")
        val wire = schema.closedOutputSchema()
        assertEquals(schema["required"], wire["required"])
        assertFalse(wire["properties"]!!.jsonObject["b"]!!.jsonObject.containsKey("anyOf"))
    }
    @Test(expected = IllegalArgumentException::class) fun `dynamic object keys require an explicit schema redesign`() {
        parseOutputSchema("""{"type":"object","additionalProperties":{"type":"string"}}""").closedOutputSchema()
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid JSON schema is not silently ignored`() { parseOutputSchema("{invalid") }
}

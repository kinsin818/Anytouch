package com.anytouch.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 每条契约：1 个 encode→decode→equals 往返用例 + 1 个左舵真实样例反序列化用例。
 * 样例逐字誊抄自 D:\SuperMa-LeftRight\docs\module_contracts.md（运行时不读该目录）。
 */
class ContractsTest {

    private val json get() = ContractJson.instance

    private inline fun <reified T> roundTrip(value: T): T {
        val text = json.encodeToString(value)
        return json.decodeFromString(text)
    }

    private inline fun <reified T> assertRoundTrip(value: T) {
        assertEquals(value, roundTrip(value))
    }

    // ---------- Observation ----------

    @Test
    fun observationRoundTrip() {
        assertRoundTrip(
            Observation(
                ok = true,
                status = "OK",
                pageId = "11111111-2222-3333-4444-555555555555",
                pageType = "survey_question",
                confidence = 0.9,
                questions = listOf(json.parseToJsonElement("""{"q_idx":0,"text":"Q?"}""").jsonObject),
                buttons = listOf(json.parseToJsonElement("""{"x":100,"y":200,"text":"下一题"}""").jsonObject),
                evidence = ObservationEvidence(vision = json.parseToJsonElement("""{"a":1}""").jsonObject),
                warnings = listOf("low_confidence"),
            )
        )
    }

    @Test
    fun observationSampleFromContractsDoc() {
        val sample = """
        {
          "ok": true,
          "status": "OK",
          "page_id": "uuid",
          "page_type": "survey_question",
          "confidence": 0.9,
          "questions": [],
          "buttons": [],
          "evidence": {
            "vision": {},
            "ocr": {},
            "yolo": {},
            "template": {},
            "cache": {},
            "training_bank": {}
          },
          "warnings": []
        }
        """.trimIndent()
        val obs = json.decodeFromString<Observation>(sample)
        assertTrue(obs.ok)
        assertEquals("OK", obs.status)
        assertEquals("uuid", obs.pageId)
        assertEquals("survey_question", obs.pageType)
        assertEquals(0.9, obs.confidence)
        assertTrue(obs.questions.isEmpty())
        assertTrue(obs.buttons.isEmpty())
        assertTrue(obs.warnings.isEmpty())
        val ev = obs.evidence
        assertEquals(0, ev.vision?.size)
        assertEquals(0, ev.ocr?.size)
        assertEquals(0, ev.yolo?.size)
        assertEquals(0, ev.template?.size)
        assertEquals(0, ev.cache?.size)
        assertEquals(0, ev.trainingBank?.size)
        // 往返一致
        assertEquals(obs, roundTrip(obs))
    }

    // ---------- ActionPlan ----------

    @Test
    fun actionPlanRoundTrip() {
        assertRoundTrip(
            ActionPlan(
                ok = true,
                status = "OK",
                planId = "plan-1",
                pageId = "page-1",
                actions = listOf(
                    Action(
                        actionId = "act-1",
                        type = ActionType.SELECT_DROPDOWN,
                        target = Point(960, 262),
                        value = json.parseToJsonElement("""{"idx":2}""").jsonObject,
                        source = ActionSource.VISION,
                        safety = ActionSafety(viewportOk = true, clickEnabled = true, requiresTransition = true),
                    )
                ),
                stopReason = "",
                fallbackPolicy = json.parseToJsonElement("""{"retry":3}""").jsonObject,
            )
        )
    }

    @Test
    fun actionPlanSampleFromContractsDoc() {
        val sample = """
        {
          "ok": true,
          "status": "OK",
          "plan_id": "uuid",
          "page_id": "uuid",
          "actions": [],
          "stop_reason": "",
          "fallback_policy": {}
        }
        """.trimIndent()
        val plan = json.decodeFromString<ActionPlan>(sample)
        assertTrue(plan.ok)
        assertEquals("OK", plan.status)
        assertEquals("uuid", plan.planId)
        assertEquals("uuid", plan.pageId)
        assertTrue(plan.actions.isEmpty())
        assertEquals("", plan.stopReason)
        assertEquals(0, plan.fallbackPolicy.size)
        assertEquals(plan, roundTrip(plan))
    }

    // ---------- Action ----------

    @Test
    fun actionRoundTrip() {
        assertRoundTrip(
            Action(
                actionId = "a-1",
                type = "click",
                target = Point(10, 20),
                value = null,
                source = ActionSource.NODE,
                safety = ActionSafety(viewportOk = true, clickEnabled = false, requiresTransition = false),
            )
        )
    }

    @Test
    fun actionSampleFromContractsDoc() {
        val sample = """
        {
          "action_id": "uuid",
          "type": "select_dropdown",
          "target": {"x": 960, "y": 262},
          "value": {"idx": 2},
          "source": "vision",
          "safety": {
            "viewport_ok": true,
            "click_enabled": true,
            "requires_transition": true
          }
        }
        """.trimIndent()
        val action = json.decodeFromString<Action>(sample)
        assertEquals("uuid", action.actionId)
        assertEquals(ActionType.SELECT_DROPDOWN, action.type)
        assertEquals(Point(960, 262), action.target)
        assertEquals(2, action.value?.get("idx")?.jsonPrimitive?.int)
        assertEquals(ActionSource.VISION, action.source)
        assertTrue(action.safety.viewportOk)
        assertTrue(action.safety.clickEnabled)
        assertTrue(action.safety.requiresTransition)
        assertEquals(action, roundTrip(action))
    }

    // ---------- ActionResult ----------

    @Test
    fun actionResultRoundTrip() {
        assertRoundTrip(
            ActionResult(
                ok = false,
                status = "RECOVERED",
                actionId = "a-1",
                backend = "interception",
                landed = Point(1, 2),
                recovery = StopReason(
                    code = StopCode.SELECT_RETRY_EXHAUSTED,
                    severity = StopSeverity.STOP,
                    message = "Dropdown did not change after retry budget",
                ),
                details = json.parseToJsonElement("""{"attempts":3}""").jsonObject,
            )
        )
        // recovery = null 也要能往返
        assertRoundTrip(
            ActionResult(ok = true, status = "SUCCESS", actionId = "a-2", backend = "interception")
        )
    }

    @Test
    fun actionResultSampleFromContractsDoc() {
        val sample = """
        {
          "ok": true,
          "status": "SUCCESS",
          "action_id": "uuid",
          "backend": "interception",
          "landed": {"x": 960, "y": 262},
          "recovery": null,
          "details": {}
        }
        """.trimIndent()
        val result = json.decodeFromString<ActionResult>(sample)
        assertTrue(result.ok)
        assertEquals("SUCCESS", result.status)
        assertEquals("uuid", result.actionId)
        assertEquals("interception", result.backend)
        assertEquals(Point(960, 262), result.landed)
        assertNull(result.recovery)
        assertEquals(0, result.details.size)
        assertEquals(result, roundTrip(result))
    }

    // ---------- Command ----------

    @Test
    fun commandRoundTrip() {
        assertRoundTrip(
            Command(
                commandId = "c-1",
                type = "start",
                source = "youshu_ui",
                payload = json.parseToJsonElement("""{"task":"survey-1"}""").jsonObject,
                confirm = "YES",
            )
        )
    }

    @Test
    fun commandSampleFromContractsDoc() {
        val sample = """
        {
          "command_id": "uuid",
          "type": "start",
          "source": "youshu_ui",
          "payload": {},
          "confirm": ""
        }
        """.trimIndent()
        val cmd = json.decodeFromString<Command>(sample)
        assertEquals("uuid", cmd.commandId)
        assertEquals("start", cmd.type)
        assertEquals("youshu_ui", cmd.source)
        assertEquals(0, cmd.payload.size)
        assertEquals("", cmd.confirm)
        assertEquals(cmd, roundTrip(cmd))
    }

    // ---------- StopReason ----------

    @Test
    fun stopReasonRoundTrip() {
        assertRoundTrip(
            StopReason(
                code = StopCode.SELECT_RETRY_EXHAUSTED,
                severity = "STOP",
                message = "Dropdown did not change after retry budget",
                evidence = json.parseToJsonElement("""{"retries":3}""").jsonObject,
            )
        )
    }

    @Test
    fun stopReasonSampleFromContractsDoc() {
        val sample = """
        {
          "code": "SELECT_RETRY_EXHAUSTED",
          "severity": "STOP",
          "message": "Dropdown did not change after retry budget",
          "evidence": {}
        }
        """.trimIndent()
        val sr = json.decodeFromString<StopReason>(sample)
        assertEquals(StopCode.SELECT_RETRY_EXHAUSTED, sr.code)
        assertEquals("STOP", sr.severity)
        assertEquals("Dropdown did not change after retry budget", sr.message)
        assertNotNull(sr.evidence)
        assertEquals(0, sr.evidence.size)
        assertEquals(sr, roundTrip(sr))
    }

    // ---------- 缺省语义 ----------

    @Test
    fun defaultSemanticsAndUnknownFields() {
        // 最小 JSON：缺省字段全部按默认值补齐；未知字段被忽略
        val minimal = """
        {
          "action_id": "x",
          "type": "click",
          "source": "node",
          "future_field": {"ignored": true}
        }
        """.trimIndent()
        val action = json.decodeFromString<Action>(minimal)
        assertNull(action.target)
        assertNull(action.value)
        assertEquals(ActionSafety(), action.safety) // 三门禁缺省全 false

        val stop = json.decodeFromString<StopReason>("""{"code":"TIMEOUT","severity":"WARN","message":"m","unknown":1}""")
        assertEquals(StopCode.TIMEOUT, stop.code)
        assertEquals(StopSeverity.WARN, stop.severity)
        assertEquals(0, stop.evidence.size)
    }
}

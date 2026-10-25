package com.immoral.location.core.protocol

import com.immoral.location.core.Reference
import com.immoral.location.core.model.EngineState
import com.immoral.location.core.model.GeoPoint
import com.immoral.location.core.model.MoveMode
import com.immoral.location.core.model.RouteState
import com.immoral.location.core.model.SimulationConfig
import com.immoral.location.core.route.RoutePlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bridge constants and the JSON codec used by the app <-> framework protocol. */
class ProtocolCodecTest {
    private val commands: List<Command> = listOf(
        Command.Ping,
        Command.GetState,
        Command.SetConfig(
            SimulationConfig(
                mode = MoveMode.ROUTE,
                updateIntervalMs = 500L,
                independentMode = true,
                independentPackages = setOf("com.example.app"),
            ),
        ),
        Command.Start,
        Command.Stop,
        Command.SetPosition(Reference.BEIJING),
        Command.SetJoystick(0.5f, -0.25f),
        Command.ReleaseJoystick,
        Command.SetHeading(271.5),
        Command.StartRoute(RoutePlan(listOf(Reference.BEIJING, Reference.SHANGHAI), repeat = true)),
        Command.PlayRoute,
        Command.PauseRoute,
        Command.StopRoute,
        Command.ResetSteps(1_234L),
        Command.ResetSteps(),
    )

    @Test
    fun bridgeConstantsMatchTheContract() {
        assertEquals("io.github.twtwilight.immorallocation", Bridge.MODULE_PACKAGE)
        assertEquals(
            "io.github.twtwilight.immorallocation.permission.CONTROL",
            Bridge.PERMISSION_CONTROL,
        )
        assertEquals("com.immoral.location.action.COMMAND", Bridge.ACTION_COMMAND)
        assertEquals("com.immoral.location.action.REPLY", Bridge.ACTION_REPLY)
        assertEquals("com.immoral.location.extra.PAYLOAD", Bridge.EXTRA_PAYLOAD)
        assertEquals("cmd", Codec.DISCRIMINATOR)
        assertTrue(CORE_ENGINE_VERSION.isNotBlank())
    }

    @Test
    fun everyCommandSurvivesARoundTrip() {
        for (command in commands) {
            val encoded = Codec.encodeCommand(command)
            val decoded = Codec.decodeCommand(encoded)
            assertNotNull(decoded, "failed to decode ${command::class.simpleName} from $encoded")
            assertEquals(command, decoded, "round trip changed $encoded")
        }
    }

    @Test
    fun encodedCommandsCarryTheDiscriminator() {
        for (command in commands) {
            val encoded = Codec.encodeCommand(command)
            assertTrue(
                encoded.contains("\"${Codec.DISCRIMINATOR}\""),
                "missing discriminator: $encoded",
            )
            assertTrue(
                encoded.contains(command::class.simpleName ?: ""),
                "missing type name: $encoded",
            )
        }
    }

    @Test
    fun decodedCommandsAreTheRightSubtype() {
        assertTrue(Codec.decodeCommand(Codec.encodeCommand(Command.Ping)) is Command.Ping)
        assertTrue(Codec.decodeCommand(Codec.encodeCommand(Command.Start)) is Command.Start)
        assertTrue(
            Codec.decodeCommand(Codec.encodeCommand(Command.SetPosition(Reference.BEIJING))) is
                Command.SetPosition,
        )
    }

    @Test
    fun setPositionKeepsTheExactCoordinates() {
        val point = GeoPoint(39.9042, 116.4074, 43.5)
        val decoded = Codec.decodeCommand(Codec.encodeCommand(Command.SetPosition(point)))
        assertEquals(Command.SetPosition(point), decoded)
    }

    @Test
    fun setJoystickKeepsFloatPrecision() {
        val decoded = Codec.decodeCommand(Codec.encodeCommand(Command.SetJoystick(-0.75f, 1f)))
        assertEquals(Command.SetJoystick(-0.75f, 1f), decoded)
    }

    @Test
    fun malformedCommandPayloadsReturnNull() {
        val garbage = listOf(
            "",
            "   ",
            "not json at all",
            "{",
            "}",
            "[]",
            "null",
            "42",
            "\"SetPosition\"",
            "{}",
            "{\"cmd\":null}",
        )
        for (payload in garbage) {
            assertNull(Codec.decodeCommand(payload), "expected null for: $payload")
        }
    }

    @Test
    fun unknownCommandsReturnNull() {
        assertNull(Codec.decodeCommand("{\"cmd\":\"Teleport\"}"))
        assertNull(Codec.decodeCommand("{\"cmd\":\"com.immoral.location.core.protocol.Nope\"}"))
    }

    @Test
    fun commandsWithMissingFieldsReturnNull() {
        assertNull(Codec.decodeCommand("{\"cmd\":\"SetPosition\"}"))
        assertNull(Codec.decodeCommand("{\"cmd\":\"SetJoystick\",\"x\":1.0}"))
        assertNull(Codec.decodeCommand("{\"cmd\":\"StartRoute\"}"))
        assertNull(Codec.decodeCommand("{\"cmd\":\"SetConfig\"}"))
    }

    @Test
    fun unknownExtraKeysAreTolerated() {
        val payload = "{\"cmd\":\"Ping\",\"futureField\":123}"
        assertEquals(Command.Ping, Codec.decodeCommand(payload))
    }

    @Test
    fun startRouteRoundTripsTheWholePlan() {
        val plan = RoutePlan(
            points = listOf(Reference.BEIJING, Reference.SHANGHAI, Reference.GUANGZHOU),
            repeat = true,
            speedMps = 12.5,
        )
        val decoded = Codec.decodeCommand(Codec.encodeCommand(Command.StartRoute(plan)))
        assertEquals(Command.StartRoute(plan), decoded)
    }

    @Test
    fun resetStepsDefaultIsZero() {
        val decoded = Codec.decodeCommand("{\"cmd\":\"ResetSteps\"}")
        assertEquals(Command.ResetSteps(0L), decoded)
    }

    @Test
    fun repliesRoundTripWithAStateSnapshot() {
        val reply = BridgeReply(
            ok = false,
            error = "boom",
            state = EngineState(
                running = true,
                mode = MoveMode.JOYSTICK,
                routeState = RouteState.PAUSED,
                point = Reference.BEIJING,
                speedMps = 1.4,
                bearingDeg = 90.0,
                steps = 42L,
                headingDeg = 88.0,
                routeProgress = 0.25,
                updatedAtMs = 1_700_000_000_000L,
                activePackages = listOf("com.example.app"),
                engineVersion = CORE_ENGINE_VERSION,
            ),
            engineLoaded = true,
            moduleVersion = "1.0.0",
        )
        val decoded = Codec.decodeReply(Codec.encodeReply(reply))
        assertEquals(reply, decoded)
    }

    @Test
    fun defaultRepliesAreOk() {
        val decoded = Codec.decodeReply(Codec.encodeReply(BridgeReply()))
        assertNotNull(decoded)
        assertTrue(decoded.ok)
        assertNull(decoded.error)
        assertNull(decoded.state)
        assertEquals(false, decoded.engineLoaded)
    }

    @Test
    fun malformedRepliesReturnNull() {
        assertNull(Codec.decodeReply(""))
        assertNull(Codec.decodeReply("{"))
        assertNull(Codec.decodeReply("nonsense"))
        assertNull(Codec.decodeReply("[1,2,3]"))
        assertNull(Codec.decodeReply("{\"ok\":\"yes\"}"))
    }

    @Test
    fun replyFromTheFrameworkIsReadableWithoutAState() {
        val payload = "{\"ok\":true,\"engineLoaded\":true,\"moduleVersion\":\"2.1.0\"}"
        val decoded = Codec.decodeReply(payload)
        assertNotNull(decoded)
        assertTrue(decoded.ok)
        assertTrue(decoded.engineLoaded)
        assertEquals("2.1.0", decoded.moduleVersion)
        assertNull(decoded.state)
    }
}

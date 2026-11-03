package app.simt.echo.transfer

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W20-02 gate: the ECHO Tap v1 wire contract, frozen as golden bytes.
 *
 * The SELECT command and a full multi-chunk exchange are asserted as exact
 * byte sequences, so a later refactor of the encoder cannot quietly change
 * what a phone actually sends. Everything else here is a boundary the
 * protocol must refuse: wrong tag, wrong command, zero/oversized reads,
 * non-contiguous offsets, a replaced offer and an expired one.
 */
class NfcApduProtocolTest {

    private fun invitation(size: Int): ByteArray =
        ByteArray(size) { (('A'.code + it % 26)).toByte() }

    private class Rig(
        invitation: ByteArray = ByteArray(300) { (('A'.code + it % 26)).toByte() },
        generation: Long = 1L,
        expiresAt: Long = 10_000L,
    ) {
        var snapshot: NfcOfferSnapshot? = NfcOfferSnapshot(
            generation = generation,
            offerId = ByteArray(8) { (it + 1).toByte() },
            invitation = invitation,
            expiresAtMonotonicMs = expiresAt,
        )
        var now = 0L
        var interactive = true
        val reads = mutableListOf<Long>()
        val presenter = NfcApduPresenter(
            snapshot = { snapshot },
            nowMs = { now },
            interactive = { interactive },
            onInvitationRead = { reads += it },
        )

        fun exchange(): ByteArray {
            val select = presenter.respond(NfcApduProtocol.selectCommand())
            val declared = NfcApduProtocol.parseSelectResponse(select).length
            val out = ArrayList<Byte>(declared)
            var offset = 0
            while (offset < declared) {
                val count = minOf(NfcApduProtocol.MAX_READ_BYTES, declared - offset)
                val reply = presenter.respond(NfcApduProtocol.readCommand(offset, count))
                out += NfcApduProtocol.parseReadResponse(reply, count).toList()
                offset += count
            }
            return out.toByteArray()
        }
    }

    // ── golden bytes ─────────────────────────────────────────────

    @Test
    fun selectCommandIsExactlyTheDocumentedAidExchange() {
        val expected = byteArrayOf(
            0x00, 0xA4.toByte(), 0x04, 0x00, 0x06,
            0xF0.toByte(), 0x45, 0x43, 0x48, 0x4F, 0x01,
            0x00,
        )
        assertContentEquals(expected, NfcApduProtocol.selectCommand())
    }

    @Test
    fun readCommandEncodesOffsetBigEndianAndLe() {
        assertContentEquals(
            byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 224.toByte()),
            NfcApduProtocol.readCommand(0, 224),
        )
        assertContentEquals(
            byteArrayOf(0x00, 0xB0.toByte(), 0x01, 0x2C, 0x10),
            NfcApduProtocol.readCommand(300, 16),
        )
    }

    @Test
    fun selectResponseIsFrozenAsGoldenBytes() {
        val rig = Rig(invitation = invitation(300))
        val response = rig.presenter.respond(NfcApduProtocol.selectCommand())
        val expected = byteArrayOf(
            0x45, 0x43, 0x48, 0x4F, // "ECHO"
            0x01, // version
            1, 2, 3, 4, 5, 6, 7, 8, // random offer id
            0x01, 0x2C.toByte(), // 300
            0x90.toByte(), 0x00, // SW_OK
        )
        assertContentEquals(expected, response)
    }

    @Test
    fun fullExchangeReassemblesTheInvitationAndAnnouncesOnce() {
        val original = invitation(300)
        val rig = Rig(invitation = original)
        assertContentEquals(original, rig.exchange())
        // Two chunks (224 + 76), one observation.
        assertEquals(listOf(1L), rig.reads)
    }

    @Test
    fun unicodeBytesSplitAcrossChunksReassembleIdentically() {
        // Multi-byte UTF-8 deliberately crosses the 224-byte chunk boundary.
        val text = "simt-echo://r?v=1&n=" + "🚪".repeat(120)
        val bytes = text.encodeToByteArray()
        val rig = Rig(invitation = bytes)
        assertContentEquals(bytes, rig.exchange())
        assertEquals(text, rig.exchange().decodeToString())
    }

    // ── refusals ─────────────────────────────────────────────────

    @Test
    fun readBeforeSelectHasNoOffer() {
        val rig = Rig()
        val reply = rig.presenter.respond(NfcApduProtocol.readCommand(0, 16))
        assertEquals(NfcApduProtocol.SW_NO_OFFER, NfcApduProtocol.statusOf(reply))
    }

    @Test
    fun nothingPublishedOrLockedOrExpiredIsNoOffer() {
        val rig = Rig()
        rig.snapshot = null
        assertEquals(NfcApduProtocol.SW_NO_OFFER, NfcApduProtocol.statusOf(rig.presenter.respond(NfcApduProtocol.selectCommand())))

        rig.snapshot = NfcOfferSnapshot(2L, ByteArray(8), invitation(20), 10_000L)
        rig.interactive = false
        assertEquals(NfcApduProtocol.SW_NO_OFFER, NfcApduProtocol.statusOf(rig.presenter.respond(NfcApduProtocol.selectCommand())))

        rig.interactive = true
        rig.now = 10_000L
        assertEquals(NfcApduProtocol.SW_NO_OFFER, NfcApduProtocol.statusOf(rig.presenter.respond(NfcApduProtocol.selectCommand())))
    }

    @Test
    fun wrongAidAndWrongCommandsAreRefused() {
        val rig = Rig()
        val wrongAid = NfcApduProtocol.selectCommand().also { it[5] = 0x01 }
        assertEquals(NfcApduProtocol.SW_WRONG_AID, NfcApduProtocol.statusOf(rig.presenter.respond(wrongAid)))

        val unknownIns = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, 0x10)
        assertEquals(NfcApduProtocol.SW_UNKNOWN_INS, NfcApduProtocol.statusOf(rig.presenter.respond(unknownIns)))

        val unknownCla = byteArrayOf(0x80.toByte(), 0xA4.toByte(), 0x04, 0x00, 0x06)
        assertEquals(NfcApduProtocol.SW_UNKNOWN_CLA, NfcApduProtocol.statusOf(rig.presenter.respond(unknownCla)))

        // Malformed SELECT: wrong Lc.
        val badLc = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05, 0x00)
        assertEquals(NfcApduProtocol.SW_WRONG_LENGTH, NfcApduProtocol.statusOf(rig.presenter.respond(badLc)))
        // Too short to be an APDU at all.
        assertEquals(NfcApduProtocol.SW_WRONG_LENGTH, NfcApduProtocol.statusOf(rig.presenter.respond(byteArrayOf(0x00))))
    }

    @Test
    fun zeroAndOversizeReadsAndBadOffsetsAreRefused() {
        val rig = Rig(invitation = invitation(300))
        rig.presenter.respond(NfcApduProtocol.selectCommand())

        assertEquals(
            NfcApduProtocol.SW_BAD_OFFSET,
            NfcApduProtocol.statusOf(rig.presenter.respond(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x00))),
        )
        // Le is one byte: 0xFF is 255 > the 224 ceiling.
        assertEquals(
            NfcApduProtocol.SW_BAD_OFFSET,
            NfcApduProtocol.statusOf(rig.presenter.respond(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0xFF.toByte()))),
        )
        // A read that runs past the declared length.
        assertEquals(
            NfcApduProtocol.SW_BAD_OFFSET,
            NfcApduProtocol.statusOf(rig.presenter.respond(NfcApduProtocol.readCommand(224, 224))),
        )
        // A non-contiguous offset cannot skip ahead.
        assertEquals(
            NfcApduProtocol.SW_BAD_OFFSET,
            NfcApduProtocol.statusOf(rig.presenter.respond(NfcApduProtocol.readCommand(100, 16))),
        )
    }

    @Test
    fun lastChunkRetransmissionRepeatsTheBytesAndAnnouncesOnce() {
        val rig = Rig(invitation = invitation(300))
        rig.presenter.respond(NfcApduProtocol.selectCommand())
        val first = NfcApduProtocol.parseReadResponse(
            rig.presenter.respond(NfcApduProtocol.readCommand(0, 224)),
            224,
        )
        val last = NfcApduProtocol.parseReadResponse(
            rig.presenter.respond(NfcApduProtocol.readCommand(224, 76)),
            76,
        )
        val again = NfcApduProtocol.parseReadResponse(
            rig.presenter.respond(NfcApduProtocol.readCommand(224, 76)),
            76,
        )
        assertContentEquals(last, again, "a retry re-answers with the same bytes")
        assertContentEquals(invitation(300), first + last)
        assertEquals(listOf(1L), rig.reads, "a retransmitted final chunk is one tap, not two")
        assertEquals(76, again.size)
    }

    @Test
    fun replacedOfferInvalidatesTheTransactionAndNeverMixesBytes() {
        val rig = Rig(invitation = invitation(300))
        rig.presenter.respond(NfcApduProtocol.selectCommand())
        rig.presenter.respond(NfcApduProtocol.readCommand(0, 224))

        // The user toggled a crop: a new generation, different bytes.
        val replacement = ByteArray(300) { 'z'.code.toByte() }
        rig.snapshot = NfcOfferSnapshot(
            generation = 2L,
            offerId = ByteArray(8) { (it + 9).toByte() },
            invitation = replacement,
            expiresAtMonotonicMs = 10_000L,
        )
        val reply = rig.presenter.respond(NfcApduProtocol.readCommand(224, 76))
        assertEquals(NfcApduProtocol.SW_NO_OFFER, NfcApduProtocol.statusOf(reply))
        assertTrue(rig.reads.isEmpty())

        // A fresh SELECT starts the new offer cleanly, from byte zero.
        assertContentEquals(replacement, rig.exchange())
    }

    @Test
    fun fieldLossResetsTheTransactionButNotTheOffer() {
        val rig = Rig(invitation = invitation(300))
        rig.presenter.respond(NfcApduProtocol.selectCommand())
        rig.presenter.respond(NfcApduProtocol.readCommand(0, 224))
        rig.presenter.deactivate()

        assertEquals(
            NfcApduProtocol.SW_NO_OFFER,
            NfcApduProtocol.statusOf(rig.presenter.respond(NfcApduProtocol.readCommand(224, 76))),
        )
        // Reconnecting to the same offer restarts from the beginning.
        assertContentEquals(invitation(300), rig.exchange())
    }

    @Test
    fun shortAndErrorResponsesFailBeforeAnyCopy() {
        assertFailsWith<ApduStatusException> {
            NfcApduProtocol.parseReadResponse(byteArrayOf(0x01, 0x02), 10)
        }
        assertFailsWith<ApduStatusException> {
            NfcApduProtocol.parseReadResponse(
                ByteArray(10) { 0 } + byteArrayOf(0x69, 0x85.toByte()),
                10,
            )
        }
        // Trailing garbage beyond the exactly-Le bytes is not accepted.
        assertFailsWith<ApduStatusException> {
            NfcApduProtocol.parseReadResponse(ByteArray(11) { 0 } + byteArrayOf(0x90.toByte(), 0x00), 10)
        }
        val wrongMagic = byteArrayOf(0x45, 0x43, 0x48, 0x00) +
            ByteArray(11) { 0 } + byteArrayOf(0x90.toByte(), 0x00)
        assertFailsWith<ApduStatusException> { NfcApduProtocol.parseSelectResponse(wrongMagic) }

        val badVersion = byteArrayOf(0x45, 0x43, 0x48, 0x4F, 0x02) +
            ByteArray(8) { 0 } + byteArrayOf(0x00, 0x10) + byteArrayOf(0x90.toByte(), 0x00)
        assertFailsWith<ApduStatusException> { NfcApduProtocol.parseSelectResponse(badVersion) }

        // A declared length of zero is malformed for this protocol.
        val zeroLength = byteArrayOf(0x45, 0x43, 0x48, 0x4F, 0x01) +
            ByteArray(8) { 0 } + byteArrayOf(0x00, 0x00) + byteArrayOf(0x90.toByte(), 0x00)
        assertFailsWith<ApduStatusException> { NfcApduProtocol.parseSelectResponse(zeroLength) }
    }

    @Test
    fun assemblerRequiresContiguousBytesAndIgnoresARepeat() {
        val assembler = NfcInvitationAssembler(10)
        assertTrue(assembler.accept(0, ByteArray(4) { 1 }))
        assertFalse(assembler.accept(0, ByteArray(4) { 1 }), "the same chunk twice advances nothing")
        assertFailsWith<IllegalArgumentException> { assembler.accept(5, ByteArray(2) { 2 }) }
        assertTrue(assembler.accept(4, ByteArray(6) { 3 }))
        assertTrue(assembler.complete)
        assertEquals(List(4) { 1 } + List(6) { 3 }, assembler.bytes().map { it.toInt() })
    }

    @Test
    fun incompleteAssemblyNeverEmitsBytesAndCanRestart() {
        val assembler = NfcInvitationAssembler(10)
        assembler.accept(0, ByteArray(4) { 1 })
        assertFalse(assembler.complete)
        assertFailsWith<IllegalArgumentException> { assembler.bytes() }
        assembler.invalidate()
        assertTrue(assembler.accept(0, ByteArray(10) { 7 }))
        assertEquals(10, assembler.bytes().size)
    }

    @Test
    fun protocolValueObjectsNeverFormatTheInvitation() {
        val snapshot = NfcOfferSnapshot(3L, ByteArray(8), invitation(300), 1L)
        assertFalse(snapshot.toString().contains("AAAA"), snapshot.toString())
        assertEquals("NfcOfferSnapshot(generation=3, bytes=300)", snapshot.toString())
        // Arrays are copied, so a caller cannot mutate pinned bytes.
        val source = invitation(4)
        val pinned = NfcOfferSnapshot(1L, ByteArray(8), source, 1L)
        source[0] = 0
        assertEquals('A'.code.toByte(), pinned.invitation[0])
    }
}

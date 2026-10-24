package com.ascargon.rocketshow.midi;

import org.junit.jupiter.api.Test;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.SysexMessage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MidiMessageParserTest {

    // Feed the bytes one by one like the serial MIDI input does and return the parsed messages as hex strings
    private List<String> parse(MidiMessageParser parser, String hexBytes) throws Exception {
        List<String> messages = new ArrayList<>();

        for (byte b : HexFormat.of().parseHex(hexBytes.replace(" ", ""))) {
            Optional<MidiMessage> midiMessage = parser.offerByte(b);
            midiMessage.ifPresent(message -> messages.add(toHex(message)));
        }

        return messages;
    }

    private List<String> parse(String hexBytes) throws Exception {
        return parse(new MidiMessageParser(), hexBytes);
    }

    private String toHex(MidiMessage midiMessage) {
        return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(midiMessage.getMessage(), 0, midiMessage.getLength());
    }

    @Test
    void parsesChannelMessages() throws Exception {
        assertEquals(List.of("90 3C 64", "85 3C 00", "B0 07 7F", "C3 05", "D0 40", "E0 00 40"),
                parse("90 3C 64 85 3C 00 B0 07 7F C3 05 D0 40 E0 00 40"));
    }

    @Test
    void ignoresDataBytesBeforeTheFirstStatusByte() throws Exception {
        assertEquals(List.of("90 3C 64"), parse("3C 64 90 3C 64"));
    }

    @Test
    void parsesMessagesSplitAcrossReads() throws Exception {
        MidiMessageParser parser = new MidiMessageParser();

        assertEquals(List.of(), parse(parser, "90 3C"));
        assertEquals(List.of("90 3C 64"), parse(parser, "64"));
    }

    @Test
    void repeatsTheRunningStatusForThreeByteMessages() throws Exception {
        assertEquals(List.of("90 3C 64", "90 3E 64", "90 40 00"), parse("90 3C 64 3E 64 40 00"));
    }

    @Test
    void repeatsTheRunningStatusForTwoByteMessages() throws Exception {
        assertEquals(List.of("C0 05", "C0 06", "C0 07"), parse("C0 05 06 07"));
    }

    @Test
    void deliversRealTimeMessagesInsideAnotherMessage() throws Exception {
        assertEquals(List.of("F8", "90 3C 64"), parse("90 3C F8 64"));
    }

    @Test
    void keepsTheRunningStatusAfterRealTimeMessages() throws Exception {
        assertEquals(List.of("90 3C 64", "F8", "90 3E 64"), parse("90 3C 64 F8 3E 64"));
    }

    @Test
    void parsesSystemCommonMessages() throws Exception {
        assertEquals(List.of("F1 20", "F2 10 02", "F3 05", "F6"), parse("F1 20 F2 10 02 F3 05 F6"));
    }

    @Test
    void parsesSysEx() throws Exception {
        MidiMessageParser parser = new MidiMessageParser();
        List<MidiMessage> messages = new ArrayList<>();

        for (byte b : HexFormat.of().parseHex("F07E7F0601F7")) {
            parser.offerByte(b).ifPresent(messages::add);
        }

        assertEquals(1, messages.size());
        assertInstanceOf(SysexMessage.class, messages.getFirst());
        assertEquals("F0 7E 7F 06 01 F7", toHex(messages.getFirst()));
    }

    @Test
    void deliversRealTimeMessagesInsideSysEx() throws Exception {
        assertEquals(List.of("F8", "F0 7E 7F F7"), parse("F0 7E F8 7F F7"));
    }

    @Test
    void recoversFromSysExWithoutEndByte() throws Exception {
        // E.g. the cable was unplugged during a SysEx dump. The next status byte ends the incomplete SysEx.
        assertEquals(List.of("90 3C 64", "90 3E 64"), parse("F0 7E 7F 90 3C 64 90 3E 64"));
    }

    @Test
    void parsesByteBuffers() throws Exception {
        Optional<MidiMessage> midiMessage = new MidiMessageParser().offerByteBuffer(ByteBuffer.wrap(HexFormat.of().parseHex("903C64")));

        assertTrue(midiMessage.isPresent());
        assertEquals("90 3C 64", toHex(midiMessage.get()));
    }

}

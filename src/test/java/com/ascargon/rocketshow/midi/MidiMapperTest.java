package com.ascargon.rocketshow.midi;

import org.junit.jupiter.api.Test;

import javax.sound.midi.ShortMessage;
import javax.sound.midi.SysexMessage;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MidiMapperTest {

    private ChannelMapping getChannelMapping(int channelFrom, int channelTo) {
        ChannelMapping channelMapping = new ChannelMapping();
        channelMapping.setChannelFrom(channelFrom);
        channelMapping.setChannelTo(channelTo);
        return channelMapping;
    }

    private ShortMessage map(int command, int channel, int data1, int data2, MidiMapping midiMapping) throws Exception {
        ShortMessage shortMessage = new ShortMessage(command, channel, data1, data2);
        MidiMapper.processMidiEvent(shortMessage, midiMapping);
        return shortMessage;
    }

    @Test
    void keepsNotesWithoutMapping() throws Exception {
        ShortMessage shortMessage = map(ShortMessage.NOTE_ON, 3, 60, 100, new MidiMapping());

        assertEquals(3, shortMessage.getChannel());
        assertEquals(60, shortMessage.getData1());
        assertEquals(100, shortMessage.getData2());
    }

    @Test
    void mapsTheChannel() throws Exception {
        MidiMapping midiMapping = new MidiMapping();
        midiMapping.getChannelMap().add(getChannelMapping(0, 5));

        assertEquals(5, map(ShortMessage.NOTE_ON, 0, 60, 100, midiMapping).getChannel());
        assertEquals(1, map(ShortMessage.NOTE_ON, 1, 60, 100, midiMapping).getChannel());
    }

    @Test
    void addsTheChannelOffsetAfterTheChannelMap() throws Exception {
        MidiMapping midiMapping = new MidiMapping();
        midiMapping.getChannelMap().add(getChannelMapping(0, 5));
        midiMapping.setChannelOffset(2);

        assertEquals(7, map(ShortMessage.NOTE_ON, 0, 60, 100, midiMapping).getChannel());
        assertEquals(3, map(ShortMessage.NOTE_OFF, 1, 60, 0, midiMapping).getChannel());
    }

    @Test
    void addsTheNoteOffset() throws Exception {
        MidiMapping midiMapping = new MidiMapping();
        midiMapping.setNoteOffset(12);

        ShortMessage shortMessage = map(ShortMessage.NOTE_ON, 0, 60, 100, midiMapping);

        assertEquals(72, shortMessage.getData1());
        assertEquals(100, shortMessage.getData2());
    }

    @Test
    void usesTheChannelMapOfTheParent() throws Exception {
        MidiMapping parent = new MidiMapping();
        parent.getChannelMap().add(getChannelMapping(0, 9));

        MidiMapping midiMapping = new MidiMapping();
        midiMapping.setParent(parent);

        assertEquals(9, map(ShortMessage.NOTE_ON, 0, 60, 100, midiMapping).getChannel());
    }

    @Test
    void prefersTheOwnChannelMapOverTheParent() throws Exception {
        MidiMapping parent = new MidiMapping();
        parent.getChannelMap().add(getChannelMapping(0, 9));

        MidiMapping midiMapping = new MidiMapping();
        midiMapping.getChannelMap().add(getChannelMapping(0, 4));
        midiMapping.setParent(parent);

        assertEquals(4, map(ShortMessage.NOTE_ON, 0, 60, 100, midiMapping).getChannel());
    }

    @Test
    void mapsOnlyNotes() throws Exception {
        MidiMapping midiMapping = new MidiMapping();
        midiMapping.getChannelMap().add(getChannelMapping(0, 5));
        midiMapping.setNoteOffset(12);

        ShortMessage controlChange = map(ShortMessage.CONTROL_CHANGE, 0, 7, 127, midiMapping);
        assertEquals(0, controlChange.getChannel());
        assertEquals(7, controlChange.getData1());

        ShortMessage programChange = new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 5, 0);
        MidiMapper.processMidiEvent(programChange, midiMapping);
        assertEquals(0, programChange.getChannel());
        assertEquals(5, programChange.getData1());
    }

    @Test
    void keepsSysEx() throws Exception {
        byte[] data = HexFormat.of().parseHex("F07E7F0601F7");
        SysexMessage sysexMessage = new SysexMessage(data, data.length);
        MidiMapping midiMapping = new MidiMapping();
        midiMapping.setNoteOffset(12);

        MidiMapper.processMidiEvent(sysexMessage, midiMapping);

        assertArrayEquals(data, sysexMessage.getMessage());
    }

}

package com.ascargon.rocketshow.midi;

import javax.sound.midi.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Optional;

public class MidiMessageParser {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean inSysEx = false;
    private int runningStatus = -1;

    public static MidiMessage createMidiMessageFromBytes(byte[] data, int length) throws InvalidMidiDataException {
        int status = data[0] & 0xFF;

        if ((status & 0xF0) == 0xF0) {
            if (status == 0xF0) {
                SysexMessage sysex = new SysexMessage();
                sysex.setMessage(data, length);
                return sysex;
            } else {
                ShortMessage sysMsg = new ShortMessage();
                if (length >= 2) {
                    sysMsg.setMessage(status, data[1] & 0xFF, (length > 2 ? data[2] & 0xFF : 0));
                } else {
                    sysMsg.setMessage(status);
                }
                return sysMsg;
            }
        } else {
            ShortMessage sm = new ShortMessage();
            if (length >= 3) {
                sm.setMessage(data[0] & 0xFF, data[1] & 0xFF, data[2] & 0xFF);
            } else if (length == 2) {
                sm.setMessage(data[0] & 0xFF, data[1] & 0xFF, 0);
            } else {
                sm.setMessage(data[0] & 0xFF, 0, 0);
            }
            return sm;
        }
    }

    public Optional<MidiMessage> offerByte(byte b) throws InvalidMidiDataException {
        int ub = b & 0xFF;

        // Real-time messages may appear anywhere, even inside other messages. Return them right away without
        // touching the message being collected.
        if (ub >= 0xF8) {
            return Optional.of(createMidiMessageFromBytes(new byte[]{b}, 1));
        }

        // Start of SysEx
        if (ub == 0xF0) {
            inSysEx = true;
            runningStatus = -1;
            buffer.reset();
            buffer.write(b);
            return Optional.empty();
        }

        // Handle SysEx accumulation
        if (inSysEx) {
            if (ub == 0xF7) {
                // End of SysEx
                buffer.write(b);
                inSysEx = false;
                byte[] sysex = buffer.toByteArray();
                buffer.reset();
                SysexMessage message = new SysexMessage();
                message.setMessage(sysex, sysex.length);
                return Optional.of(message);
            }

            if (ub < 0x80) {
                buffer.write(b);
                return Optional.empty();
            }

            // Any other status byte ends an incomplete SysEx (e.g. the end byte got lost). Drop it and continue
            // with the new message.
            inSysEx = false;
            buffer.reset();
        }

        // Status byte (>= 0x80)
        if (ub >= 0x80) {
            buffer.reset();
            buffer.write(b);

            // Only channel messages set a running status. System common messages cancel it.
            runningStatus = ub < 0xF0 ? ub : -1;

            // If 1-byte message (e.g. tune request), return now
            if (getMidiMessageLength(ub) == 1) {
                buffer.reset();
                return Optional.of(createMidiMessageFromBytes(new byte[]{b}, 1));
            }

            return Optional.empty(); // wait for data bytes
        }

        // Data byte without a status byte in the buffer -> continue with the running status, if there is one
        if (buffer.size() == 0) {
            if (runningStatus < 0) {
                return Optional.empty();
            }
            buffer.write(runningStatus);
        }

        buffer.write(b);
        byte[] msgBytes = buffer.toByteArray();

        if (msgBytes.length == getMidiMessageLength(msgBytes[0] & 0xFF)) {
            buffer.reset();
            return Optional.of(createMidiMessageFromBytes(msgBytes, msgBytes.length));
        }

        return Optional.empty(); // still accumulating
    }

    public Optional<MidiMessage> offerBytes(byte[] bytes) throws InvalidMidiDataException {
        Optional<MidiMessage> result = Optional.empty();
        for (byte b : bytes) {
            Optional<MidiMessage> m = offerByte(b);
            if (m.isPresent()) result = m;
        }
        return result;
    }

    public Optional<MidiMessage> offerByteBuffer(ByteBuffer byteBuffer) throws InvalidMidiDataException {
        Optional<MidiMessage> result = Optional.empty();
        while (byteBuffer.hasRemaining()) {
            Optional<MidiMessage> m = offerByte(byteBuffer.get());
            if (m.isPresent()) result = m;
        }
        return result;
    }

    private int getMidiMessageLength(int statusByte) {
        if (statusByte < 0x80) return 0;
        if (statusByte >= 0xF8) return 1; // real-time
        if (statusByte >= 0xF0) {
            return switch (statusByte) {
                case 0xF1, 0xF3 -> 2;
                case 0xF2 -> 3;
                case 0xF6, 0xF8, 0xFA, 0xFB, 0xFC, 0xFE, 0xFF -> 1;
                default -> 1;
            };
        }
        return switch (statusByte & 0xF0) {
            case 0xC0, 0xD0 -> 2;
            default -> 3;
        };
    }
}

package com.shadowdb;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Helper class for PostgreSQL Frontend/Backend Protocol 3.0 framing and parsing.
 */
public class PostgresProtocolHelper {

    public static final byte MESSAGE_TYPE_QUERY = 'Q';
    public static final byte MESSAGE_TYPE_ROW_DESCRIPTION = 'T';
    public static final byte MESSAGE_TYPE_DATA_ROW = 'D';
    public static final byte MESSAGE_TYPE_COMMAND_COMPLETE = 'C';
    public static final byte MESSAGE_TYPE_READY_FOR_QUERY = 'Z';

    /**
     * Checks whether the given byte array ends with a PostgreSQL 'ReadyForQuery' (Z) packet.
     * Format: 'Z' (1 byte) + length int32 (4 bytes = 5) + status (1 byte: 'I' / 'T' / 'E').
     */
    public static boolean isReadyForQueryAtEnd(byte[] data) {
        if (data == null || data.length < 6) {
            return false;
        }
        int idx = data.length - 6;
        return data[idx] == MESSAGE_TYPE_READY_FOR_QUERY &&
               data[idx + 1] == 0 && data[idx + 2] == 0 &&
               data[idx + 3] == 0 && data[idx + 4] == 5;
    }

    /**
     * Creates a standard PostgreSQL Frontend 3.0 Simple Query packet ('Q').
     */
    public static byte[] createQueryPacket(String sql) {
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        int length = 4 + sqlBytes.length + 1; // 4 bytes for length int itself + string + null terminator
        byte[] packet = new byte[1 + length];
        packet[0] = MESSAGE_TYPE_QUERY;
        packet[1] = (byte) ((length >> 24) & 0xFF);
        packet[2] = (byte) ((length >> 16) & 0xFF);
        packet[3] = (byte) ((length >> 8) & 0xFF);
        packet[4] = (byte) (length & 0xFF);
        System.arraycopy(sqlBytes, 0, packet, 5, sqlBytes.length);
        packet[packet.length - 1] = 0; // null terminator
        return packet;
    }

    /**
     * Creates a mock PostgreSQL Backend response for a SELECT query containing one column and one row.
     */
    public static byte[] createMockSelectResponse(String columnName, String value) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // 1. RowDescription ('T')
            byte[] colBytes = columnName.getBytes(StandardCharsets.UTF_8);
            int rowDescLen = 4 + 2 + (colBytes.length + 1 + 4 + 2 + 4 + 2 + 4 + 2);
            dos.writeByte(MESSAGE_TYPE_ROW_DESCRIPTION);
            dos.writeInt(rowDescLen);
            dos.writeShort(1); // 1 column
            dos.write(colBytes);
            dos.writeByte(0); // null terminator for col name
            dos.writeInt(0);  // table OID
            dos.writeShort(1); // column attribute number
            dos.writeInt(25);  // data type OID (TEXT)
            dos.writeShort(-1); // data type size
            dos.writeInt(-1);  // type modifier
            dos.writeShort(0); // format code (text)

            // 2. DataRow ('D')
            byte[] valBytes = value.getBytes(StandardCharsets.UTF_8);
            int dataRowLen = 4 + 2 + (4 + valBytes.length);
            dos.writeByte(MESSAGE_TYPE_DATA_ROW);
            dos.writeInt(dataRowLen);
            dos.writeShort(1); // 1 column value
            dos.writeInt(valBytes.length);
            dos.write(valBytes);

            // 3. CommandComplete ('C')
            byte[] tagBytes = "SELECT 1".getBytes(StandardCharsets.UTF_8);
            int cmdCompleteLen = 4 + tagBytes.length + 1;
            dos.writeByte(MESSAGE_TYPE_COMMAND_COMPLETE);
            dos.writeInt(cmdCompleteLen);
            dos.write(tagBytes);
            dos.writeByte(0);

            // 4. ReadyForQuery ('Z')
            dos.writeByte(MESSAGE_TYPE_READY_FOR_QUERY);
            dos.writeInt(5);
            dos.writeByte('I'); // Idle

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to construct mock SELECT response", e);
        }
    }

    /**
     * Creates a mock PostgreSQL Backend response for an UPDATE/INSERT query.
     */
    public static byte[] createMockCommandCompleteResponse(String tag) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // 1. CommandComplete ('C')
            byte[] tagBytes = tag.getBytes(StandardCharsets.UTF_8);
            int cmdCompleteLen = 4 + tagBytes.length + 1;
            dos.writeByte(MESSAGE_TYPE_COMMAND_COMPLETE);
            dos.writeInt(cmdCompleteLen);
            dos.write(tagBytes);
            dos.writeByte(0);

            // 2. ReadyForQuery ('Z')
            dos.writeByte(MESSAGE_TYPE_READY_FOR_QUERY);
            dos.writeInt(5);
            dos.writeByte('I'); // Idle

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to construct mock command complete response", e);
        }
    }
}

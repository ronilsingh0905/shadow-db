package com.shadowdb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper class for PostgreSQL Frontend/Backend Protocol 3.0 framing and parsing.
 */
public class PostgresProtocolHelper {

    public static final byte MESSAGE_TYPE_QUERY = 'Q';
    public static final byte MESSAGE_TYPE_ROW_DESCRIPTION = 'T';
    public static final byte MESSAGE_TYPE_DATA_ROW = 'D';
    public static final byte MESSAGE_TYPE_COMMAND_COMPLETE = 'C';
    public static final byte MESSAGE_TYPE_READY_FOR_QUERY = 'Z';

    public record QueryResult(
            boolean isSelect,
            List<String> columns,
            List<List<String>> rows,
            String commandTag,
            int rowCount
    ) {
        public static QueryResult empty() {
            return new QueryResult(false, Collections.emptyList(), Collections.emptyList(), "EMPTY", 0);
        }
    }

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
     * Creates a multi-column, multi-row PostgreSQL Backend response for a SELECT query.
     */
    public static byte[] createSelectResponse(List<String> columnNames, List<List<String>> rows) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {

            // 1. RowDescription ('T')
            dos.writeByte(MESSAGE_TYPE_ROW_DESCRIPTION);
            int fieldsLen = 0;
            for (String col : columnNames) {
                byte[] colBytes = col.getBytes(StandardCharsets.UTF_8);
                fieldsLen += (colBytes.length + 1 + 18);
            }
            dos.writeInt(4 + 2 + fieldsLen);
            dos.writeShort(columnNames.size());
            short colAttr = 1;
            for (String col : columnNames) {
                byte[] colBytes = col.getBytes(StandardCharsets.UTF_8);
                dos.write(colBytes);
                dos.writeByte(0); // null terminator
                dos.writeInt(0);  // table OID
                dos.writeShort(colAttr++);
                dos.writeInt(25); // data type OID (TEXT)
                dos.writeShort(-1); // data type size
                dos.writeInt(-1); // type modifier
                dos.writeShort(0); // format code (text)
            }

            // 2. DataRow ('D')
            if (rows != null) {
                for (List<String> row : rows) {
                    dos.writeByte(MESSAGE_TYPE_DATA_ROW);
                    int rowLen = 4 + 2;
                    for (String val : row) {
                        if (val == null) {
                            rowLen += 4;
                        } else {
                            byte[] valBytes = val.getBytes(StandardCharsets.UTF_8);
                            rowLen += (4 + valBytes.length);
                        }
                    }
                    dos.writeInt(rowLen);
                    dos.writeShort(row.size());
                    for (String val : row) {
                        if (val == null) {
                            dos.writeInt(-1);
                        } else {
                            byte[] valBytes = val.getBytes(StandardCharsets.UTF_8);
                            dos.writeInt(valBytes.length);
                            dos.write(valBytes);
                        }
                    }
                }
            }

            // 3. CommandComplete ('C')
            int count = (rows != null) ? rows.size() : 0;
            byte[] tagBytes = ("SELECT " + count).getBytes(StandardCharsets.UTF_8);
            dos.writeByte(MESSAGE_TYPE_COMMAND_COMPLETE);
            dos.writeInt(4 + tagBytes.length + 1);
            dos.write(tagBytes);
            dos.writeByte(0);

            // 4. ReadyForQuery ('Z')
            dos.writeByte(MESSAGE_TYPE_READY_FOR_QUERY);
            dos.writeInt(5);
            dos.writeByte('I'); // Idle

            dos.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to construct SELECT response", e);
        }
    }

    /**
     * Creates a mock PostgreSQL Backend response for a SELECT query containing one column and one row.
     */
    public static byte[] createMockSelectResponse(String columnName, String value) {
        return createSelectResponse(List.of(columnName), List.of(List.of(value)));
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

    /**
     * Decodes PostgreSQL wire protocol response bytes into a structured QueryResult.
     */
    public static QueryResult decodeQueryResult(byte[] data) {
        if (data == null || data.length < 5) {
            return QueryResult.empty();
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream dis = new DataInputStream(bais)) {

            List<String> columns = new ArrayList<>();
            List<List<String>> rows = new ArrayList<>();
            String commandTag = "UNKNOWN";
            boolean isSelect = false;

            while (dis.available() > 0) {
                byte type = dis.readByte();
                int length = dis.readInt();
                int payloadLength = length - 4;

                if (type == MESSAGE_TYPE_ROW_DESCRIPTION) {
                    isSelect = true;
                    short numFields = dis.readShort();
                    columns = new ArrayList<>(numFields);
                    for (int i = 0; i < numFields; i++) {
                        ByteArrayOutputStream strBuf = new ByteArrayOutputStream();
                        byte b;
                        while ((b = dis.readByte()) != 0) {
                            strBuf.write(b);
                        }
                        columns.add(strBuf.toString(StandardCharsets.UTF_8));
                        dis.readInt();   // table OID
                        dis.readShort(); // col attr
                        dis.readInt();   // type OID
                        dis.readShort(); // type size
                        dis.readInt();   // type mod
                        dis.readShort(); // format code
                    }
                } else if (type == MESSAGE_TYPE_DATA_ROW) {
                    short numCols = dis.readShort();
                    List<String> row = new ArrayList<>(numCols);
                    for (int i = 0; i < numCols; i++) {
                        int valLen = dis.readInt();
                        if (valLen == -1) {
                            row.add(null);
                        } else {
                            byte[] valBytes = new byte[valLen];
                            dis.readFully(valBytes);
                            row.add(new String(valBytes, StandardCharsets.UTF_8));
                        }
                    }
                    rows.add(row);
                } else if (type == MESSAGE_TYPE_COMMAND_COMPLETE) {
                    byte[] tagBytes = new byte[payloadLength];
                    dis.readFully(tagBytes);
                    int effLen = payloadLength;
                    while (effLen > 0 && tagBytes[effLen - 1] == 0) {
                        effLen--;
                    }
                    commandTag = new String(tagBytes, 0, effLen, StandardCharsets.UTF_8);
                } else if (type == MESSAGE_TYPE_READY_FOR_QUERY) {
                    if (payloadLength > 0) {
                        dis.skipBytes(payloadLength);
                    }
                    break;
                } else {
                    if (payloadLength > 0) {
                        dis.skipBytes(payloadLength);
                    }
                }
            }

            return new QueryResult(isSelect, columns, rows, commandTag, rows.size());
        } catch (Exception e) {
            return new QueryResult(false, List.of(), List.of(), "ERROR: " + e.getMessage(), 0);
        }
    }
}

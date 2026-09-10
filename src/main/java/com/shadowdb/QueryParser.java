package com.shadowdb;

import io.netty.buffer.ByteBuf;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Module 2.0: Query Classification & Key Hashing.
 * Inspects incoming byte buffers, classifies queries using JSqlParser,
 * computes SHA-256 hash keys for SELECT statements, and extracts target table names.
 */
public class QueryParser {

    private static final Logger log = LoggerFactory.getLogger(QueryParser.class);

    public enum QueryType {
        SELECT,
        INSERT,
        UPDATE,
        DELETE,
        OTHER
    }

    public record ParsedQuery(
            QueryType type,
            String rawSql,
            String normalizedSql,
            String hashKey,
            Set<String> tableNames
    ) {
        public boolean isSelect() {
            return type == QueryType.SELECT;
        }

        public boolean isMutation() {
            return type == QueryType.INSERT || type == QueryType.UPDATE || type == QueryType.DELETE;
        }
    }

    /**
     * Inspects inbound ByteBuf to extract SQL query string.
     * Supports both PostgreSQL wire protocol 'Q' (Simple Query) packets
     * and raw ASCII SQL packets.
     */
    public static String extractSql(ByteBuf buf) {
        if (buf == null || buf.readableBytes() < 5) {
            return null;
        }

        int readerIndex = buf.readerIndex();
        byte messageType = buf.getByte(readerIndex);

        // PostgreSQL Frontend protocol 3.0: 'Q' (0x51) Simple Query
        if (messageType == 'Q') {
            int length = buf.getInt(readerIndex + 1); // length includes self (4 bytes)
            if (length >= 5 && buf.readableBytes() >= length + 1) {
                int sqlLength = length - 4;
                byte[] bytes = new byte[sqlLength];
                buf.getBytes(readerIndex + 5, bytes);
                // Exclude trailing null byte if present
                int effectiveLength = sqlLength;
                while (effectiveLength > 0 && bytes[effectiveLength - 1] == 0) {
                    effectiveLength--;
                }
                return new String(bytes, 0, effectiveLength, StandardCharsets.UTF_8).trim();
            }
        }

        // Fallback: check if raw text packet (e.g. testing / raw TCP)
        int readable = buf.readableBytes();
        int previewLen = Math.min(readable, 4096);
        byte[] preview = new byte[previewLen];
        buf.getBytes(readerIndex, preview);
        String previewStr = new String(preview, StandardCharsets.UTF_8).trim();

        // Strip any trailing semicolons or whitespace for inspection
        String upper = previewStr.toUpperCase(Locale.ROOT);
        if (upper.startsWith("SELECT") || upper.startsWith("INSERT") ||
            upper.startsWith("UPDATE") || upper.startsWith("DELETE")) {
            return previewStr;
        }

        return null;
    }

    /**
     * Parses the SQL query string using JSqlParser, classifies it,
     * extracts table names, and produces SHA-256 HashKey if SELECT.
     */
    public static ParsedQuery parse(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            return new ParsedQuery(QueryType.OTHER, rawSql, null, null, Collections.emptySet());
        }

        try {
            // Strip trailing semicolons for consistent parsing
            String cleanSql = rawSql.trim();
            while (cleanSql.endsWith(";")) {
                cleanSql = cleanSql.substring(0, cleanSql.length() - 1).trim();
            }

            Statement statement = CCJSqlParserUtil.parse(cleanSql);
            Set<String> tables = extractTables(statement);
            String normalizedSql = statement.toString().trim();

            if (statement instanceof Select) {
                String hashKey = sha256Hex(normalizedSql);
                return new ParsedQuery(QueryType.SELECT, rawSql, normalizedSql, hashKey, tables);
            } else if (statement instanceof Insert) {
                return new ParsedQuery(QueryType.INSERT, rawSql, normalizedSql, null, tables);
            } else if (statement instanceof Update) {
                return new ParsedQuery(QueryType.UPDATE, rawSql, normalizedSql, null, tables);
            } else if (statement instanceof Delete) {
                return new ParsedQuery(QueryType.DELETE, rawSql, normalizedSql, null, tables);
            } else {
                return new ParsedQuery(QueryType.OTHER, rawSql, normalizedSql, null, tables);
            }
        } catch (Exception e) {
            log.debug("Failed to parse SQL via JSqlParser: {}", rawSql, e);
            // Fallback: regex-based heuristic if JSqlParser encounters non-standard syntax
            return parseFallback(rawSql);
        }
    }

    private static Set<String> extractTables(Statement statement) {
        Set<String> tables = new HashSet<>();
        try {
            TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
            List<String> list = tablesNamesFinder.getTableList(statement);
            if (list != null) {
                for (String t : list) {
                    if (t != null && !t.isBlank()) {
                        tables.add(cleanTableName(t));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("TablesNamesFinder failed: {}", e.getMessage());
        }

        // Direct statement type fallbacks
        if (statement instanceof Insert insert && insert.getTable() != null) {
            tables.add(cleanTableName(insert.getTable().getName()));
        } else if (statement instanceof Update update && update.getTable() != null) {
            tables.add(cleanTableName(update.getTable().getName()));
        } else if (statement instanceof Delete delete && delete.getTable() != null) {
            tables.add(cleanTableName(delete.getTable().getName()));
        }

        return Collections.unmodifiableSet(tables);
    }

    private static ParsedQuery parseFallback(String rawSql) {
        String trimmed = rawSql.trim();
        String upper = trimmed.toUpperCase(Locale.ROOT);
        QueryType type;
        if (upper.startsWith("SELECT")) {
            type = QueryType.SELECT;
        } else if (upper.startsWith("INSERT")) {
            type = QueryType.INSERT;
        } else if (upper.startsWith("UPDATE")) {
            type = QueryType.UPDATE;
        } else if (upper.startsWith("DELETE")) {
            type = QueryType.DELETE;
        } else {
            type = QueryType.OTHER;
        }

        Set<String> tables = new HashSet<>();
        // Simple heuristic extraction for table name
        if (type == QueryType.SELECT && upper.contains("FROM ")) {
            String afterFrom = trimmed.substring(upper.indexOf("FROM ") + 5).trim();
            String tableName = afterFrom.split("[\\s,;()]+")[0];
            tables.add(cleanTableName(tableName));
        } else if (type == QueryType.INSERT && upper.contains("INTO ")) {
            String afterInto = trimmed.substring(upper.indexOf("INTO ") + 5).trim();
            String tableName = afterInto.split("[\\s(]+")[0];
            tables.add(cleanTableName(tableName));
        } else if (type == QueryType.UPDATE) {
            String afterUpdate = trimmed.substring(6).trim();
            String tableName = afterUpdate.split("[\\s]+")[0];
            tables.add(cleanTableName(tableName));
        }

        String normalized = trimmed.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String hashKey = (type == QueryType.SELECT) ? sha256Hex(normalized) : null;
        return new ParsedQuery(type, rawSql, normalized, hashKey, Collections.unmodifiableSet(tables));
    }

    private static String cleanTableName(String name) {
        if (name == null) return "";
        // Remove quotes or schema prefixes if any (e.g. public.users -> users)
        String clean = name.replace("\"", "").replace("`", "").trim();
        if (clean.contains(".")) {
            clean = clean.substring(clean.lastIndexOf('.') + 1);
        }
        return clean.toLowerCase(Locale.ROOT);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}

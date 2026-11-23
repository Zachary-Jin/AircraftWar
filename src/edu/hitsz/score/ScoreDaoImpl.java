package edu.hitsz.score;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 基于文件的排行榜 DAO 实现。
 */
public class ScoreDaoImpl implements ScoreDao {

    private static final String DEFAULT_DATA_DIR = "data";
    private final Path dataDir;

    public ScoreDaoImpl() {
        this(Paths.get(DEFAULT_DATA_DIR));
    }

    public ScoreDaoImpl(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public void addRecord(ScoreRecord record) {
        try {
            Files.createDirectories(dataDir);
            try (BufferedWriter writer = Files.newBufferedWriter(
                    getFile(record.getDifficulty()),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)) {
                writer.write(record.toCsvLine());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save score record", e);
        }
    }

    @Override
    public List<ScoreRecord> getRecords(String difficulty) {
        Path file = getFile(difficulty);
        List<ScoreRow> rows = sortValidRows(file, readLines(file));
        List<ScoreRecord> records = new ArrayList<>(rows.size());
        for (ScoreRow row : rows) {
            records.add(row.record);
        }
        return records;
    }

    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load score records", e);
        }
    }

    private List<ScoreRow> sortValidRows(Path file, List<String> lines) {
        List<ScoreRow> rows = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                rows.add(new ScoreRow(ScoreRecord.fromCsvLine(line), index));
            } catch (IllegalArgumentException | DateTimeParseException e) {
                System.err.printf("跳过排行榜 %s 中格式错误的第 %d 行%n", file.getFileName(), index + 1);
            }
        }
        rows.sort(Comparator.comparingInt((ScoreRow row) -> row.record.getScore())
                .reversed()
                .thenComparing(row -> row.record.getRecordTime()));
        return rows;
    }

    @Override
    public void deleteRecord(String difficulty, int rank) {
        Path file = getFile(difficulty);
        List<String> lines = new ArrayList<>(readLines(file));
        List<ScoreRow> rows = sortValidRows(file, lines);
        int index = rank - 1;
        if (index < 0 || index >= rows.size()) {
            return;
        }
        // 按排名找到原始行，只移除该行，保留其他原始内容。
        lines.remove(rows.get(index).lineIndex);
        replaceLines(file, lines);
    }

    private void replaceLines(Path file, List<String> lines) {
        try {
            Path temporaryFile = Files.createTempFile(dataDir, "scores-", ".tmp");
            try {
                Files.write(temporaryFile, lines, StandardCharsets.UTF_8);
                try {
                    Files.move(temporaryFile, file,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporaryFile);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rewrite score records", e);
        }
    }

    private Path getFile(String difficulty) {
        return dataDir.resolve(difficulty + ".csv");
    }

    private static final class ScoreRow {
        private final ScoreRecord record;
        private final int lineIndex;

        private ScoreRow(ScoreRecord record, int lineIndex) {
            this.record = record;
            this.lineIndex = lineIndex;
        }
    }
}

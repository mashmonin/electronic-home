package com.example.electronichome.statement;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.electronichome.storage.SpooledUpload;

/** Takes an uploaded statement, checks it, keeps it and starts the making of the receipts; answers about the batches. */
@Service
class BatchService {

    static final long MAX_SIZE = 20L * 1024 * 1024;
    // the signature of the files of Excel 97-2003 (a compound document)
    private static final byte[] XLS_SIGNATURE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final StatementStorage storage;
    private final StatementParser parser;
    private final BatchRepository batches;
    private final BatchRunner runner;
    private final Clock clock;

    BatchService(StatementStorage storage, StatementParser parser, BatchRepository batches, BatchRunner runner, Clock clock) {
        this.storage = storage;
        this.parser = parser;
        this.batches = batches;
        this.runner = runner;
        this.clock = clock;
    }

    BatchResponses.Batch create(MultipartFile file, long userId) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Выберите файл ведомости");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Файл больше 20 МБ");
        }
        try (InputStream in = file.getInputStream(); SpooledUpload upload = SpooledUpload.read(in, MAX_SIZE)) {
            if (upload.size() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл пустой");
            }
            if (upload.size() < XLS_SIGNATURE.length || !Arrays.equals(Arrays.copyOf(upload.head(), XLS_SIGNATURE.length), XLS_SIGNATURE)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Нужна ведомость начислений в формате Excel 97–2003 (.xls)");
            }
            Statement statement;
            try (InputStream content = Files.newInputStream(upload.file())) {
                statement = parser.parse(content);
            } catch (StatementException e) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
            }
            if (statement.period().isAfter(YearMonth.now(clock))) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Расчётный месяц ведомости ещё не наступил");
            }
            String key = storage.save(upload.file(), upload.size());
            long id = batches.create(statement.period(), key, upload.size(), upload.sha256(), statement.accounts().size(), userId);
            runner.submit(id);
            return summary(batches.find(id).orElseThrow());
        } catch (SpooledUpload.TooLargeException e) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Файл больше 20 МБ");
        }
    }

    BatchResponses.Batches list() {
        return new BatchResponses.Batches(batches.latest().stream().map(BatchService::summary).toList());
    }

    BatchResponses.Detail get(long id) {
        BatchRepository.Batch batch = batches.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<BatchResponses.Item> items = batches.items(id).stream()
                .map(item -> new BatchResponses.Item(
                        item.accountNumber(), item.status().toLowerCase(), item.reason(), BatchStatus.reasonText(item.reason())))
                .toList();
        return new BatchResponses.Detail(
                batch.id(), batch.month().toString(), batch.status().toLowerCase(), batch.total(), batch.processed(), batch.created(),
                batch.replaced(), batch.skipped(), batch.failed(), error(batch), batch.createdAt(), batch.finishedAt(), items);
    }

    private static BatchResponses.Batch summary(BatchRepository.Batch batch) {
        return new BatchResponses.Batch(
                batch.id(), batch.month().toString(), batch.status().toLowerCase(), batch.total(), batch.processed(), batch.created(),
                batch.replaced(), batch.skipped(), batch.failed(), error(batch), batch.createdAt(), batch.finishedAt());
    }

    // the text of the failure of the whole batch, or null
    private static String error(BatchRepository.Batch batch) {
        if (batch.error() == null) {
            return null;
        }
        return BatchStatus.INTERRUPTED.equals(batch.error()) ? BatchStatus.reasonText(BatchStatus.INTERRUPTED) : "Не удалось обработать ведомость";
    }
}

package com.example.electronichome.request;

import java.nio.file.Path;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.example.electronichome.storage.ObjectKeys;
import com.example.electronichome.storage.ObjectStore;
import com.example.electronichome.storage.StorageProperties;

/**
 * Makes the request about the verification of a meter: the first message greets the administrator and carries the act
 * as an attachment. It joins the transaction of the caller (the draft of the act is saved in it): when the transaction
 * does not commit, the copy of the act in the bucket of the attachments is deleted.
 */
@Component
public class VerificationRequests {

    /** The act to attach: a file of the disk with what the storage of the acts knows about it. */
    public record ActFile(String name, String contentType, long size, String sha256, Path file) {
    }

    public record Created(long id, String number) {
    }

    private final RequestRepository requests;
    private final RequestNumbers numbers;
    private final ObjectStore store;
    private final String bucket;

    VerificationRequests(RequestRepository requests, RequestNumbers numbers, ObjectStore store, StorageProperties properties) {
        this.requests = requests;
        this.numbers = numbers;
        this.store = store;
        this.bucket = properties.attachmentsBucket();
    }

    public Created create(long authorId, long accountId, String meterNumber, ActFile act) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The request about a verification is made in the transaction of the caller");
        }
        String key = ObjectKeys.newKey();
        store.put(bucket, key, act.file(), act.size(), act.contentType());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    store.delete(bucket, key);
                }
            }
        });
        String number = numbers.next(RequestKind.VERIFICATION);
        long id = requests.insertRequest(accountId, authorId, number, RequestKind.VERIFICATION);
        long messageId = requests.insertMessage(id, authorId, "Добрый день! Выполнил проверку счетчика №" + meterNumber);
        requests.insertAttachment(messageId, new AttachmentStorage.Stored(key, act.name(), act.contentType(), act.size(), act.sha256()));
        return new Created(id, number);
    }
}

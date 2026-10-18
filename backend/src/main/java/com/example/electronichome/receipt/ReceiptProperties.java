package com.example.electronichome.receipt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the receipts ({@code receipts.*}).
 *
 * @param storage    where the PDF files are kept
 * @param maxSize    the biggest accepted file
 * @param adminToken the token that allows publishing; blank means that publishing is switched off
 */
@ConfigurationProperties("receipts")
public record ReceiptProperties(
        @DefaultValue Storage storage,
        @DefaultValue("5MB") DataSize maxSize,
        @DefaultValue("") String adminToken) {

    /** @param path the directory of the files (a volume in Docker) */
    public record Storage(@DefaultValue("./data/receipts") String path) {
    }
}

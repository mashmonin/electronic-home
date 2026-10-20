package com.example.electronichome.receipt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the receipts ({@code receipts.*}); the files are kept in the object storage ({@code storage.s3.*}).
 *
 * @param maxSize    the biggest accepted file
 * @param adminToken the token that allows publishing; blank means that publishing is switched off
 */
@ConfigurationProperties("receipts")
public record ReceiptProperties(
        @DefaultValue("5MB") DataSize maxSize,
        @DefaultValue("") String adminToken) {
}

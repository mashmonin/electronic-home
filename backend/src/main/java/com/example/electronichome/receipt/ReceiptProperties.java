package com.example.electronichome.receipt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the receipts ({@code receipts.*}); the files are kept in the object storage ({@code storage.s3.*}).
 *
 * @param maxSize    the biggest accepted file
 * @param adminToken the token that allows publishing; blank means that publishing is switched off
 * @param migration  where the files were kept before the object storage
 */
@ConfigurationProperties("receipts")
public record ReceiptProperties(
        @DefaultValue("5MB") DataSize maxSize,
        @DefaultValue("") String adminToken,
        @DefaultValue Migration migration) {

    /** @param sourcePath the directory whose files are copied into the storage once at the start; blank means none */
    public record Migration(@DefaultValue("") String sourcePath) {
    }
}

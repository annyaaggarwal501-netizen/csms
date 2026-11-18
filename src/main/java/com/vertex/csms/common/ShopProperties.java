package com.vertex.csms.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shop details printed on bills and WhatsApp messages. The values in application.properties are only the
 * starting defaults; the Owner edits the real ones in Settings (stored in shop_setting, see SettingsService).
 */
@ConfigurationProperties(prefix = "csms.shop")
public record ShopProperties(String name, String address, String gstin, String phone, String locationUrl,
                             String receiptFooter) {}

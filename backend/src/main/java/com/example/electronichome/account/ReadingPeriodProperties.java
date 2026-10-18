package com.example.electronichome.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the reading periods ({@code readings.period.*}).
 *
 * @param closingDay the last day of a period in its month; 1-28, so that every month has it
 * @param enforced   when false (testing), a meter may be read again in a period it was already read in
 */
@ConfigurationProperties("readings.period")
public record ReadingPeriodProperties(@DefaultValue("25") int closingDay, @DefaultValue("true") boolean enforced) {

    public ReadingPeriodProperties {
        if (closingDay < 1 || closingDay > 28) {
            throw new IllegalArgumentException("readings.period.closing-day must be between 1 and 28, but was " + closingDay);
        }
    }
}

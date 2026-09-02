/*
 * Copyright (c) 2017 Baidu, Inc. All Rights Reserve.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.fsg.uid.utils;

import java.util.Calendar;
import java.util.Date;

/**
 * DateUtils provides date formatting, parsing
 *
 * 注意：原版依赖 commons-lang 2.x（DateFormatUtils / DateUtils），
 * 本项目未引入 commons-lang，改用 String.format 的线程安全日期格式化实现。
 *
 * @author yutianbao
 */
public abstract class DateUtils {
    /**
     * Patterns
     */
    public static final String DAY_PATTERN = "yyyy-MM-dd";
    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DATETIME_MS_PATTERN = "yyyy-MM-dd HH:mm:ss.SSS";

    public static final Date DEFAULT_DATE = DateUtils.parseByDayPattern("1970-01-01");

    /**
     * Parse date by 'yyyy-MM-dd' pattern
     *
     * @param str
     * @return
     */
    public static Date parseByDayPattern(String str) {
        return parseDate(str, DAY_PATTERN);
    }

    /**
     * Parse date by 'yyyy-MM-dd HH:mm:ss' pattern
     *
     * @param str
     * @return
     */
    public static Date parseByDateTimePattern(String str) {
        return parseDate(str, DATETIME_PATTERN);
    }

    /**
     * Parse date without Checked exception
     *
     * @param str
     * @param pattern
     * @return
     * @throws RuntimeException when ParseException occurred
     */
    public static Date parseDate(String str, String pattern) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(pattern);
            sdf.setLenient(false);
            return sdf.parse(str);
        } catch (java.text.ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Format date into string
     *
     * @param date
     * @param pattern
     * @return
     */
    public static String formatDate(Date date, String pattern) {
        return new java.text.SimpleDateFormat(pattern).format(date);
    }

    /**
     * Format date by 'yyyy-MM-dd' pattern
     *
     * @param date
     * @return
     */
    public static String formatByDayPattern(Date date) {
        if (date != null) {
            return String.format("%tF", date);
        } else {
            return null;
        }
    }

    /**
     * Format date by 'yyyy-MM-dd HH:mm:ss' pattern
     *
     * @param date
     * @return
     */
    public static String formatByDateTimePattern(Date date) {
        return String.format("%tF %tT", date, date);
    }

    /**
     * Get current day using format date by 'yyyy-MM-dd HH:mm:ss' pattern
     *
     * @return
     * @author yebo
     */
    public static String getCurrentDayByDayPattern() {
        Calendar cal = Calendar.getInstance();
        return formatByDayPattern(cal.getTime());
    }

}

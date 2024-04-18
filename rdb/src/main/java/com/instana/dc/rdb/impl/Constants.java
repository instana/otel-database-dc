/*
 * (c) Copyright IBM Corp. 2024
 * (c) Copyright Instana Inc.
 */

package com.instana.dc.rdb.impl;

public class Constants {

    public static final String SINGLE_QUOTES = "'";
    public static final String COMMA = ",";
    public static final String BIN = "/bin";
    public static final String ONCONFIG = "onconfig.";
    public static final String SQL_HOSTS = "sqlhosts.";
    public static final String BLANK_SPACE = " ";
    public static final String SQL_COUNT_SCRIPT = "sql_count.sh";
    public static final String TRANSACTION_COUNT_SCRIPT = "transaction_count.sh";
    public static final String IO_READ_COUNT_SCRIPT = "io_read_count.sh";
    public static final String IO_WRITE_COUNT_SCRIPT = "io_write_count.sh";
    public static final String MEMORY_UTILIZATION_SCRIPT = "memory_utilization.sh";
    public static final String DISK_READ_SCRIPT = "disk_read.sh";
    public static final String DISK_WRITE_SCRIPT = "disk_write.sh";
    public static final String TASK_WAIT_COUNT_SCRIPT = "task_wait_count.sh";
    public static  final String TOTAL_SESSION_COUNT_SCRIPT ="session_count.sh";
    public static final String ACTIVE_SESSION_COUNT_SCRIPT = "active_session_count.sh";
    private Constants() {
        //Private constructor
    }

}

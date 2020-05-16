/******************************************************************************
 *  Copyright 2015 by OLTPBenchmark Project                                   *
 *                                                                            *
 *  Licensed under the Apache License, Version 2.0 (the "License");           *
 *  you may not use this file except in compliance with the License.          *
 *  You may obtain a copy of the License at                                   *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 *  Unless required by applicable law or agreed to in writing, software       *
 *  distributed under the License is distributed on an "AS IS" BASIS,         *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *  See the License for the specific language governing permissions and       *
 *  limitations under the License.                                            *
 ******************************************************************************/

package com.oltpbenchmark.types;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * List of the database management systems that we support
 * in the framework.
 *
 * @author pavlo
 */
public enum DatabaseType {

    /**
     * Parameters:
     * (2) Should SQLUtil.getInsertSQL escape table/col names
     * (3) Should SQLUtil.getInsertSQL include col names
     */
    DB2(true, false),
    MYSQL(true, false),
    MYROCKS(true, false),
    POSTGRES(false, false),
    ORACLE(true, false),
    SQLSERVER(true, false),
    SQLITE(true, false),
    HSQLDB(false, false),
    H2(true, false),
    MONETDB(false, false),
    NUODB(true, false),
    TIMESTEN(true, false),
    CASSANDRA(true, true),
    MEMSQL(true, false),
    COCKROACHDB(false, false),
    ;

    private DatabaseType(boolean escapeNames,
                         boolean includeColNames) {
        this.escapeNames = escapeNames;
        this.includeColNames = includeColNames;
    }

    /**
     * If this flag is set to true, then the framework will escape names in
     * the INSERT queries
     */
    private final boolean escapeNames;

    /**
     * If this flag is set to true, then the framework will include the column names
     * when generating INSERT queries for loading data.
     */
    private final boolean includeColNames;


    // ---------------------------------------------------------------
    // ACCESSORS
    // ----------------------------------------------------------------

    /**
     * Returns true if the framework should escape the names of columns/tables when
     * generating SQL to load in data for the target database type.
     *
     * @return
     */
    public boolean shouldEscapeNames() {
        return (this.escapeNames);
    }

    /**
     * Returns true if the framework should include the names of columns when
     * generating SQL to load in data for the target database type.
     *
     * @return
     */
    public boolean shouldIncludeColumnNames() {
        return (this.includeColNames);
    }


    // ----------------------------------------------------------------
    // STATIC METHODS + MEMBERS
    // ----------------------------------------------------------------

    protected static final Map<Integer, DatabaseType> idx_lookup = new HashMap<>();
    protected static final Map<String, DatabaseType> name_lookup = new HashMap<>();

    static {
        for (DatabaseType vt : EnumSet.allOf(DatabaseType.class)) {
            DatabaseType.idx_lookup.put(vt.ordinal(), vt);
            DatabaseType.name_lookup.put(vt.name().toUpperCase(), vt);
        }
    }

    public static DatabaseType get(String name) {
        DatabaseType ret = DatabaseType.name_lookup.get(name.toUpperCase());
        return (ret);
    }
}

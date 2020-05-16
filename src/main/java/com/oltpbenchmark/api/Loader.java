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


package com.oltpbenchmark.api;

import com.oltpbenchmark.WorkloadConfiguration;
import com.oltpbenchmark.catalog.Catalog;
import com.oltpbenchmark.catalog.Column;
import com.oltpbenchmark.catalog.Table;
import com.oltpbenchmark.types.DatabaseType;
import com.oltpbenchmark.util.Histogram;
import com.oltpbenchmark.util.SQLUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Random;

/**
 * @author pavlo
 */
public abstract class Loader<T extends BenchmarkModule> {
    protected static final Logger LOG = LoggerFactory.getLogger(Loader.class);

    protected final T benchmark;

    protected final WorkloadConfiguration workConf;
    protected final double scaleFactor;
    private final Histogram<String> tableSizes = new Histogram<>(true);

    public Loader(T benchmark) {
        this.benchmark = benchmark;
        this.workConf = benchmark.getWorkloadConfiguration();
        this.scaleFactor = workConf.getScaleFactor();
    }

    /**
     * Each Loader will generate a list of Runnable objects that
     * will perform the loading operation for the benchmark.
     * The number of threads that will be launched at the same time
     * depends on the number of cores that are available. But they are
     * guaranteed to execute in the order specified in the list.
     * You will have to use your own protections if there are dependencies between
     * threads (i.e., if one table needs to be loaded before another).
     * <p>
     * Each LoaderThread will be given a Connection handle to the DBMS when
     * it is invoked.
     * <p>
     * If the benchmark does <b>not</b> support multi-threaded loading yet,
     * then this method should return null.
     *
     * @return The list of LoaderThreads the framework will launch.
     */
    public abstract List<LoaderThread> createLoaderThreads() throws SQLException;

    public void addToTableCount(String tableName, int delta) {
        this.tableSizes.put(tableName, delta);
    }

    public Histogram<String> getTableCounts() {
        return (this.tableSizes);
    }

    public DatabaseType getDatabaseType() {
        return (this.workConf.getDBType());
    }

    /**
     * Return the database's catalog
     */
    public Catalog getCatalog() {
        return (this.benchmark.getCatalog());
    }

    /**
     * Get the catalog object for the given table name
     *
     * @param tableName
     * @return
     */
    @Deprecated
    public Table getTableCatalog(String tableName) {
        Table catalog_tbl = this.benchmark.getCatalog().getTable(tableName.toUpperCase());

        return (catalog_tbl);
    }

    /**
     * Get the pre-seeded Random generator for this Loader invocation
     *
     * @return
     */
    public Random rng() {
        return (this.benchmark.rng());
    }


    /**
     * Method that can be overriden to specifically unload the tables of the
     * database. In the default implementation it checks for tables from the
     * catalog to delete them using SQL. Any subclass can inject custom behavior
     * here.
     *
     * @param catalog The catalog containing all loaded tables
     * @throws SQLException
     */
    public void unload(Connection conn, Catalog catalog) throws SQLException {

        conn.setTransactionIsolation(workConf.getIsolationMode());
        try (Statement st = conn.createStatement()) {
            for (Table catalog_tbl : catalog.getTables()) {
                LOG.debug(String.format("Deleting data from table %s", catalog_tbl.getName()));
                String sql = "DELETE FROM " + catalog_tbl.getEscapedName();
                st.execute(sql);
            } // FOR
            conn.commit();
        }
    }

    protected void updateAutoIncrement(Connection conn, Column catalog_col, int value) throws SQLException {
        String sql = null;
        switch (getDatabaseType()) {
            case POSTGRES:
                String seqName = SQLUtil.getSequenceName(getDatabaseType(), catalog_col);

                sql = String.format("SELECT setval(%s, %d)", seqName.toLowerCase(), value);
                break;
            default:
                // Nothing!
        }
        if (sql != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Updating %s auto-increment counter with value '%d'", catalog_col.fullName(), value));
            }
            try (Statement stmt = conn.createStatement()) {
                boolean result = stmt.execute(sql);
                if (LOG.isDebugEnabled()) {
                    LOG.debug(String.format("%s => [%s]", sql, result));
                }
            }
        }
    }
}

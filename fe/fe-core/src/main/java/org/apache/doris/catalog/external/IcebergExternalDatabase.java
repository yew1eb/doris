// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.catalog.external;

import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.datasource.ExternalCatalog;
import org.apache.doris.datasource.InitDatabaseLog;
import org.apache.doris.datasource.iceberg.IcebergExternalCatalog;
import org.apache.doris.persist.gson.GsonPostProcessable;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class IcebergExternalDatabase extends ExternalDatabase<IcebergExternalTable> implements GsonPostProcessable {

    private static final Logger LOG = LogManager.getLogger(IcebergExternalDatabase.class);
    // Cache of table name to table id.
    private Map<String, Long> tableNameToId = Maps.newConcurrentMap();
    @SerializedName(value = "idToTbl")
    private Map<Long, IcebergExternalTable> idToTbl = Maps.newConcurrentMap();

    public IcebergExternalDatabase(ExternalCatalog extCatalog, Long id, String name) {
        super(extCatalog, id, name);
    }

    public void replayInitDb(InitDatabaseLog log, ExternalCatalog catalog) {
        Map<String, Long> tmpTableNameToId = Maps.newConcurrentMap();
        Map<Long, IcebergExternalTable> tmpIdToTbl = Maps.newConcurrentMap();
        for (int i = 0; i < log.getRefreshCount(); i++) {
            IcebergExternalTable table = getTableForReplay(log.getRefreshTableIds().get(i));
            tmpTableNameToId.put(table.getName(), table.getId());
            tmpIdToTbl.put(table.getId(), table);
        }
        for (int i = 0; i < log.getCreateCount(); i++) {
            IcebergExternalTable table = new IcebergExternalTable(log.getCreateTableIds().get(i),
                    log.getCreateTableNames().get(i), name, (IcebergExternalCatalog) catalog);
            tmpTableNameToId.put(table.getName(), table.getId());
            tmpIdToTbl.put(table.getId(), table);
        }
        tableNameToId = tmpTableNameToId;
        idToTbl = tmpIdToTbl;
        initialized = true;
    }

    public void setTableExtCatalog(ExternalCatalog extCatalog) {
        for (IcebergExternalTable table : idToTbl.values()) {
            table.setCatalog(extCatalog);
        }
    }

    @Override
    protected void init() {
        InitDatabaseLog initDatabaseLog = new InitDatabaseLog();
        initDatabaseLog.setType(InitDatabaseLog.Type.HMS);
        initDatabaseLog.setCatalogId(extCatalog.getId());
        initDatabaseLog.setDbId(id);
        List<String> tableNames = extCatalog.listTableNames(null, name);
        if (tableNames != null) {
            Map<String, Long> tmpTableNameToId = Maps.newConcurrentMap();
            Map<Long, IcebergExternalTable> tmpIdToTbl = Maps.newHashMap();
            for (String tableName : tableNames) {
                long tblId;
                if (tableNameToId != null && tableNameToId.containsKey(tableName)) {
                    tblId = tableNameToId.get(tableName);
                    tmpTableNameToId.put(tableName, tblId);
                    IcebergExternalTable table = idToTbl.get(tblId);
                    table.unsetObjectCreated();
                    tmpIdToTbl.put(tblId, table);
                    initDatabaseLog.addRefreshTable(tblId);
                } else {
                    tblId = Env.getCurrentEnv().getNextId();
                    tmpTableNameToId.put(tableName, tblId);
                    IcebergExternalTable table = new IcebergExternalTable(tblId, tableName, name,
                            (IcebergExternalCatalog) extCatalog);
                    tmpIdToTbl.put(tblId, table);
                    initDatabaseLog.addCreateTable(tblId, tableName);
                }
            }
            tableNameToId = tmpTableNameToId;
            idToTbl = tmpIdToTbl;
        }
        initialized = true;
        Env.getCurrentEnv().getEditLog().logInitExternalDb(initDatabaseLog);
    }

    @Override
    public List<IcebergExternalTable> getTables() {
        makeSureInitialized();
        return Lists.newArrayList(idToTbl.values());
    }

    @Override
    public List<IcebergExternalTable> getTablesOnIdOrder() {
        // Sort the name instead, because the id may change.
        return getTables().stream().sorted(Comparator.comparing(TableIf::getName)).collect(Collectors.toList());
    }

    @Override
    public Set<String> getTableNamesWithLock() {
        makeSureInitialized();
        return Sets.newHashSet(tableNameToId.keySet());
    }

    @Override
    public IcebergExternalTable getTableNullable(String tableName) {
        makeSureInitialized();
        if (!tableNameToId.containsKey(tableName)) {
            return null;
        }
        return idToTbl.get(tableNameToId.get(tableName));
    }

    @Override
    public IcebergExternalTable getTableNullable(long tableId) {
        makeSureInitialized();
        return idToTbl.get(tableId);
    }

    @Override
    public IcebergExternalTable getTableForReplay(long tableId) {
        return idToTbl.get(tableId);
    }

    @Override
    public void gsonPostProcess() throws IOException {
        tableNameToId = Maps.newConcurrentMap();
        for (IcebergExternalTable tbl : idToTbl.values()) {
            tableNameToId.put(tbl.getName(), tbl.getId());
        }
        rwLock = new ReentrantReadWriteLock(true);
    }

    @Override
    public void dropTable(String tableName) {
        LOG.debug("drop table [{}]", tableName);
        makeSureInitialized();
        Long tableId = tableNameToId.remove(tableName);
        if (tableId == null) {
            LOG.warn("drop table [{}] failed", tableName);
        }
        idToTbl.remove(tableId);
    }

    @Override
    public void createTable(String tableName, long tableId) {
        LOG.debug("create table [{}]", tableName);
        makeSureInitialized();
        tableNameToId.put(tableName, tableId);
        IcebergExternalTable table = new IcebergExternalTable(tableId, tableName, name,
                (IcebergExternalCatalog) extCatalog);
        idToTbl.put(tableId, table);
    }
}

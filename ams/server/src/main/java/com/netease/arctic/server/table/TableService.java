/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netease.arctic.server.table;

import com.netease.arctic.ams.api.TableIdentifier;
import com.netease.arctic.ams.api.TableMeta;
import com.netease.arctic.server.catalog.CatalogService;
import org.apache.hadoop.hive.metastore.api.MetaException;

import java.util.List;
import java.util.Map;

public interface TableService extends CatalogService, TableRuntimeManager {

  void initialize();

  /**
   * create table metadata
   * @param tableMeta   table metadata info
   * @throws MetaException when the table metadata is not match
   */
  void createTable(String catalogName, TableMeta tableMeta);

  /**
   * load the table metadata
   *
   * @param tableIdentifier table id
   * @return table metadata info
   */
  @Deprecated
  TableMetadata loadTableMetadata(TableIdentifier tableIdentifier);

  /**
   * delete the table metadata
   *
   * @param tableIdentifier     table id
   * @param deleteData          if delete the external table
   * @throws MetaException when table metadata is not match
   */
  @Deprecated
  void dropTableMetadata(TableIdentifier tableIdentifier, boolean deleteData);

  /**
   * update the arctic table properties
   *
   * @param tableIdentifier table id
   * @param properties      arctic table properties
   */
  void updateTableProperties(ServerTableIdentifier tableIdentifier, Map<String, String> properties);

  /**
   * load arctic databases name
   *
   * @return databases name list
   */
  List<String> listDatabases(String catalogName);

  /**
   * load table identifiers
   *
   * @return TableIdentifier list
   */
  List<ServerTableIdentifier> listTables();

  /**
   * load table identifiers
   *
   * @return TableIdentifier list
   */
  List<ServerTableIdentifier> listTables(String catalogName, String dbName);

  /**
   * create arctic database
   *
   */
  void createDatabase(String catalogName, String dbName);

  /**
   * drop arctic database
   *
   */
  void dropDatabase(String catalogName, String dbName);

  /**
   * load all arctic table metadata
   *
   * @return table metadata list
   */
  List<TableMetadata> listTableMetas();

  /**
   * load arctic table metadata
   *
   * @return table metadata list
   */
  List<TableMetadata> listTableMetas(String catalogName, String database);

  /**
   * check the table is existed
   *
   * @return True when the table is existed.
   */
  boolean tableExist(TableIdentifier tableIdentifier);
}
/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.spi.data.sql;

import co.cask.cdap.spi.data.transaction.TransactionException;

import java.sql.SQLException;

/**
 * Encapsulates the SQLException that caused the transaction to fail.
 */
class SqlTransactionException extends TransactionException {
  private final SQLException sqlException;

  SqlTransactionException(SQLException sqlException, Throwable cause) {
    super(String.format("Failed to execute the sql queries. Transaction failed with sql state: %s",
                        sqlException.getSQLState()), cause);
    this.sqlException = sqlException;
  }

  SQLException getSqlException() {
    return sqlException;
  }
}

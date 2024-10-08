/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.timeboundary;

import com.google.common.base.Function;
import com.google.inject.Inject;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.guava.BaseSequence;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.query.ChainedExecutionQueryRunner;
import org.apache.druid.query.Order;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryProcessingPool;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactory;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.QueryWatcher;
import org.apache.druid.query.Result;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.segment.BaseLongColumnValueSelector;
import org.apache.druid.segment.Cursor;
import org.apache.druid.segment.CursorBuildSpec;
import org.apache.druid.segment.CursorHolder;
import org.apache.druid.segment.Cursors;
import org.apache.druid.segment.Segment;
import org.apache.druid.segment.StorageAdapter;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.filter.Filters;
import org.apache.druid.utils.CollectionUtils;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.util.Iterator;

/**
 *
 */
public class TimeBoundaryQueryRunnerFactory
    implements QueryRunnerFactory<Result<TimeBoundaryResultValue>, TimeBoundaryQuery>
{
  private static final TimeBoundaryQueryQueryToolChest TOOL_CHEST = new TimeBoundaryQueryQueryToolChest();
  private final QueryWatcher queryWatcher;

  @Inject
  public TimeBoundaryQueryRunnerFactory(QueryWatcher queryWatcher)
  {
    this.queryWatcher = queryWatcher;
  }

  @Override
  public QueryRunner<Result<TimeBoundaryResultValue>> createRunner(final Segment segment)
  {
    return new TimeBoundaryQueryRunner(segment);
  }

  @Override
  public QueryRunner<Result<TimeBoundaryResultValue>> mergeRunners(
      QueryProcessingPool queryProcessingPool,
      Iterable<QueryRunner<Result<TimeBoundaryResultValue>>> queryRunners
  )
  {
    return new ChainedExecutionQueryRunner<>(queryProcessingPool, queryWatcher, queryRunners);
  }

  @Override
  public QueryToolChest<Result<TimeBoundaryResultValue>, TimeBoundaryQuery> getToolchest()
  {
    return TOOL_CHEST;
  }

  private static class TimeBoundaryQueryRunner implements QueryRunner<Result<TimeBoundaryResultValue>>
  {
    private final StorageAdapter adapter;
    private final Function<Cursor, Result<DateTime>> skipToFirstMatching;

    public TimeBoundaryQueryRunner(Segment segment)
    {
      this.adapter = segment.asStorageAdapter();
      this.skipToFirstMatching = new Function<Cursor, Result<DateTime>>()
      {
        @SuppressWarnings("ArgumentParameterSwap")
        @Override
        public Result<DateTime> apply(Cursor cursor)
        {
          if (cursor.isDone()) {
            return null;
          }
          final BaseLongColumnValueSelector timestampColumnSelector =
              cursor.getColumnSelectorFactory().makeColumnValueSelector(ColumnHolder.TIME_COLUMN_NAME);
          final DateTime timestamp = DateTimes.utc(timestampColumnSelector.getLong());
          return new Result<>(adapter.getInterval().getStart(), timestamp);
        }
      };
    }

    @Nullable
    private DateTime getTimeBoundary(StorageAdapter adapter, TimeBoundaryQuery legacyQuery, boolean descending)
    {
      final CursorBuildSpec.CursorBuildSpecBuilder bob = CursorBuildSpec.builder(makeCursorBuildSpec(legacyQuery));
      if (descending) {
        bob.setPreferredOrdering(Cursors.descendingTimeOrder());
      } else {
        bob.setPreferredOrdering(Cursors.ascendingTimeOrder());
      }

      try (final CursorHolder cursorHolder = adapter.makeCursorHolder(bob.build())) {
        Cursors.requireTimeOrdering(cursorHolder, descending ? Order.DESCENDING : Order.ASCENDING);
        final Cursor cursor = cursorHolder.asCursor();
        if (cursor == null) {
          return null;
        }
        final Result<DateTime> result = skipToFirstMatching.apply(cursor);
        return result == null ? null : result.getValue();
      }
    }

    @Override
    public Sequence<Result<TimeBoundaryResultValue>> run(
        final QueryPlus<Result<TimeBoundaryResultValue>> queryPlus,
        final ResponseContext responseContext
    )
    {
      Query<Result<TimeBoundaryResultValue>> input = queryPlus.getQuery();
      if (!(input instanceof TimeBoundaryQuery)) {
        throw new ISE("Got a [%s] which isn't a %s", input.getClass(), TimeBoundaryQuery.class);
      }

      final TimeBoundaryQuery query = (TimeBoundaryQuery) input;

      return new BaseSequence<>(
          new BaseSequence.IteratorMaker<Result<TimeBoundaryResultValue>, Iterator<Result<TimeBoundaryResultValue>>>()
          {
            @Override
            public Iterator<Result<TimeBoundaryResultValue>> make()
            {
              if (adapter == null) {
                throw new ISE(
                    "Null storage adapter found. Probably trying to issue a query against a segment being memory unmapped."
                );
              }

              DateTime minTime = null;
              DateTime maxTime = null;

              if (canUseAdapterMinMaxTime(query, adapter)) {
                if (!query.isMaxTime()) {
                  minTime = adapter.getMinTime();
                }

                if (!query.isMinTime()) {
                  maxTime = adapter.getMaxTime();
                }
              } else {
                if (!query.isMaxTime()) {
                  minTime = getTimeBoundary(adapter, query, false);
                }

                if (!query.isMinTime()) {
                  if (query.isMaxTime() || minTime != null) {
                    maxTime = getTimeBoundary(adapter, query, true);
                  }
                }
              }

              return query.buildResult(
                  adapter.getInterval().getStart(),
                  minTime,
                  maxTime
              ).iterator();
            }

            @Override
            public void cleanup(Iterator<Result<TimeBoundaryResultValue>> toClean)
            {

            }
          }
      );
    }
  }

  /**
   * Whether a particular {@link TimeBoundaryQuery} can use {@link StorageAdapter#getMinTime()} and/or
   * {@link StorageAdapter#getMaxTime()}. If false, must use {@link StorageAdapter#makeCursorHolder(CursorBuildSpec)}.
   */
  private static boolean canUseAdapterMinMaxTime(final TimeBoundaryQuery query, final StorageAdapter adapter)
  {
    if (query.getFilter() != null) {
      // We have to check which rows actually match the filter.
      return false;
    }

    if (!(query.getDataSource() instanceof TableDataSource)) {
      // In general, minTime / maxTime are only guaranteed to match data for regular tables.
      //
      // One example: an INNER JOIN can act as a filter and remove some rows. Another example: RowBasedStorageAdapter
      // (used by e.g. inline data) uses nominal interval, not actual data, for minTime / maxTime.
      return false;
    }

    final Interval queryInterval = CollectionUtils.getOnlyElement(
        query.getQuerySegmentSpec().getIntervals(),
        xs -> new IAE("Should only have one interval, got[%s]", xs)
    );

    if (!queryInterval.contains(adapter.getInterval())) {
      // Query interval does not contain adapter interval. Need to create a cursor to see the first
      // timestamp within the query interval.
      return false;
    }

    // Passed all checks.
    return true;
  }

  public static CursorBuildSpec makeCursorBuildSpec(TimeBoundaryQuery query)
  {
    return CursorBuildSpec.builder()
                          .setInterval(query.getSingleInterval())
                          .setFilter(Filters.convertToCNFFromQueryContext(query, Filters.toFilter(query.getFilter())))
                          .setVirtualColumns(query.getVirtualColumns())
                          .setQueryContext(query.context())
                          .build();
  }
}

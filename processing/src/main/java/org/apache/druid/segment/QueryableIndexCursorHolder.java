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

package org.apache.druid.segment;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import org.apache.druid.collections.bitmap.BitmapFactory;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.io.Closer;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.query.BaseQuery;
import org.apache.druid.query.BitmapResultFactory;
import org.apache.druid.query.DefaultBitmapResultFactory;
import org.apache.druid.query.OrderBy;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.QueryMetrics;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.filter.Filter;
import org.apache.druid.query.filter.FilterBundle;
import org.apache.druid.query.filter.ValueMatcher;
import org.apache.druid.query.filter.vector.VectorValueMatcher;
import org.apache.druid.query.monomorphicprocessing.RuntimeShapeInspector;
import org.apache.druid.segment.column.ColumnHolder;
import org.apache.druid.segment.column.NumericColumn;
import org.apache.druid.segment.data.Offset;
import org.apache.druid.segment.data.ReadableOffset;
import org.apache.druid.segment.historical.HistoricalCursor;
import org.apache.druid.segment.vector.BitmapVectorOffset;
import org.apache.druid.segment.vector.FilteredVectorOffset;
import org.apache.druid.segment.vector.NoFilterVectorOffset;
import org.apache.druid.segment.vector.QueryableIndexVectorColumnSelectorFactory;
import org.apache.druid.segment.vector.VectorColumnSelectorFactory;
import org.apache.druid.segment.vector.VectorCursor;
import org.apache.druid.segment.vector.VectorOffset;
import org.apache.druid.utils.CloseableUtils;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class QueryableIndexCursorHolder implements CursorHolder
{
  private static final Logger log = new Logger(QueryableIndexCursorHolder.class);
  private final QueryableIndex index;
  private final Interval interval;
  private final VirtualColumns virtualColumns;

  @Nullable
  private final List<AggregatorFactory> aggregatorFactories;
  @Nullable
  private final Filter filter;
  @Nullable
  private final QueryMetrics<? extends Query> metrics;
  private final List<OrderBy> ordering;
  private final boolean descending;
  private final QueryContext queryContext;
  private final int vectorSize;
  private final Supplier<CursorResources> resourcesSupplier;

  public QueryableIndexCursorHolder(
      QueryableIndex index,
      CursorBuildSpec cursorBuildSpec
  )
  {
    this.index = index;
    this.interval = cursorBuildSpec.getInterval();
    this.virtualColumns = cursorBuildSpec.getVirtualColumns();
    this.aggregatorFactories = cursorBuildSpec.getAggregators();
    this.filter = cursorBuildSpec.getFilter();
    // adequate for time ordering, but needs to be updated if we support cursors ordered other time as the primary
    if (Cursors.preferDescendingTimeOrdering(cursorBuildSpec)) {
      this.ordering = Collections.singletonList(OrderBy.descending(ColumnHolder.TIME_COLUMN_NAME));
      this.descending = true;
    } else {
      this.ordering = Collections.singletonList(OrderBy.ascending(ColumnHolder.TIME_COLUMN_NAME));
      this.descending = false;
    }
    this.queryContext = cursorBuildSpec.getQueryContext();
    this.vectorSize = cursorBuildSpec.getQueryContext().getVectorSize();
    this.metrics = cursorBuildSpec.getQueryMetrics();
    this.resourcesSupplier = Suppliers.memoize(() -> new CursorResources(index, virtualColumns, filter, metrics));
  }

  @Override
  public boolean canVectorize()
  {
    final ColumnInspector inspector = virtualColumns.wrapInspector(index);
    if (!virtualColumns.isEmpty()) {
      if (!queryContext.getVectorizeVirtualColumns().shouldVectorize(virtualColumns.canVectorize(inspector))) {
        return false;
      }
    }
    if (aggregatorFactories != null) {
      for (AggregatorFactory factory : aggregatorFactories) {
        if (!factory.canVectorize(inspector)) {
          return false;
        }
      }
    }

    final CursorResources resources = resourcesSupplier.get();
    final FilterBundle filterBundle = resources.filterBundle;
    if (filterBundle != null) {
      if (!filterBundle.canVectorizeMatcher()) {
        return false;
      }
    }

    // vector cursors can't iterate backwards yet
    return !descending;
  }

  @Override
  public Cursor asCursor()
  {
    if (metrics != null) {
      metrics.vectorized(false);
    }
    final Offset baseOffset;

    final CursorResources resources = resourcesSupplier.get();
    final FilterBundle filterBundle = resources.filterBundle;
    final int numRows = resources.numRows;
    final long minDataTimestamp = resources.minDataTimestamp;
    final long maxDataTimestamp = resources.maxDataTimestamp;
    final NumericColumn timestamps = resources.timestamps;
    final ColumnCache columnCache = resources.columnCache;

    // filterBundle will only be null if the filter itself is null, otherwise check to see if the filter
    // can use an index
    if (filterBundle == null || filterBundle.getIndex() == null) {
      baseOffset = descending ? new SimpleDescendingOffset(numRows) : new SimpleAscendingOffset(numRows);
    } else {
      baseOffset = BitmapOffset.of(filterBundle.getIndex().getBitmap(), descending, index.getNumRows());
    }

    final long timeStart = Math.max(interval.getStartMillis(), minDataTimestamp);
    final long timeEnd = interval.getEndMillis();

    if (descending) {
      for (; baseOffset.withinBounds(); baseOffset.increment()) {
        if (timestamps.getLongSingleValueRow(baseOffset.getOffset()) < timeEnd) {
          break;
        }
      }
    } else {
      for (; baseOffset.withinBounds(); baseOffset.increment()) {
        if (timestamps.getLongSingleValueRow(baseOffset.getOffset()) >= timeStart) {
          break;
        }
      }
    }

    final Offset offset = descending ?
                          new DescendingTimestampCheckingOffset(
                              baseOffset,
                              timestamps,
                              timeStart,
                              minDataTimestamp >= timeStart
                          ) :
                          new AscendingTimestampCheckingOffset(
                              baseOffset,
                              timestamps,
                              timeEnd,
                              maxDataTimestamp < timeEnd
                          );


    final Offset baseCursorOffset = offset.clone();
    final ColumnSelectorFactory columnSelectorFactory = new QueryableIndexColumnSelectorFactory(
        virtualColumns,
        descending,
        baseCursorOffset.getBaseReadableOffset(),
        columnCache
    );
    // filterBundle will only be null if the filter itself is null, otherwise check to see if the filter
    // needs to use a value matcher
    if (filterBundle != null && filterBundle.getMatcherBundle() != null) {
      final ValueMatcher matcher = filterBundle.getMatcherBundle()
                                               .valueMatcher(
                                                   columnSelectorFactory,
                                                   baseCursorOffset,
                                                   descending
                                               );
      final FilteredOffset filteredOffset = new FilteredOffset(baseCursorOffset, matcher);
      return new QueryableIndexCursor(filteredOffset, columnSelectorFactory);
    } else {
      return new QueryableIndexCursor(baseCursorOffset, columnSelectorFactory);
    }
  }

  @Nullable
  @Override
  public VectorCursor asVectorCursor()
  {
    final CursorResources resources = resourcesSupplier.get();
    final FilterBundle filterBundle = resources.filterBundle;
    final long minDataTimestamp = resources.minDataTimestamp;
    final long maxDataTimestamp = resources.maxDataTimestamp;
    final NumericColumn timestamps = resources.timestamps;
    final ColumnCache columnCache = resources.columnCache;
    // Wrap the remainder of cursor setup in a try, so if an error is encountered while setting it up, we don't
    // leak columns in the ColumnCache.

    // sanity check
    if (!canVectorize()) {
      close();
      throw new IllegalStateException("canVectorize()");
    }
    if (metrics != null) {
      metrics.vectorized(true);
    }


    final int startOffset;
    final int endOffset;

    if (interval.getStartMillis() > minDataTimestamp) {
      startOffset = timeSearch(timestamps, interval.getStartMillis(), 0, index.getNumRows());
    } else {
      startOffset = 0;
    }

    if (interval.getEndMillis() <= maxDataTimestamp) {
      endOffset = timeSearch(timestamps, interval.getEndMillis(), startOffset, index.getNumRows());
    } else {
      endOffset = index.getNumRows();
    }

    // filterBundle will only be null if the filter itself is null, otherwise check to see if the filter can use
    // an index
    final VectorOffset baseOffset =
        filterBundle == null || filterBundle.getIndex() == null
        ? new NoFilterVectorOffset(vectorSize, startOffset, endOffset)
        : new BitmapVectorOffset(vectorSize, filterBundle.getIndex().getBitmap(), startOffset, endOffset);

    // baseColumnSelectorFactory using baseOffset is the column selector for filtering.
    final VectorColumnSelectorFactory baseColumnSelectorFactory = makeVectorColumnSelectorFactoryForOffset(
        columnCache,
        baseOffset
    );

    // filterBundle will only be null if the filter itself is null, otherwise check to see if the filter needs to use
    // a value matcher
    if (filterBundle != null && filterBundle.getMatcherBundle() != null) {
      final VectorValueMatcher vectorValueMatcher = filterBundle.getMatcherBundle()
                                                                .vectorMatcher(baseColumnSelectorFactory, baseOffset);
      final VectorOffset filteredOffset = FilteredVectorOffset.create(
          baseOffset,
          vectorValueMatcher
      );

      // Now create the cursor and column selector that will be returned to the caller.
      final VectorColumnSelectorFactory filteredColumnSelectorFactory = makeVectorColumnSelectorFactoryForOffset(
          columnCache,
          filteredOffset
      );
      return new QueryableIndexVectorCursor(filteredColumnSelectorFactory, filteredOffset, vectorSize);
    } else {
      return new QueryableIndexVectorCursor(baseColumnSelectorFactory, baseOffset, vectorSize);
    }
  }

  @Override
  public List<OrderBy> getOrdering()
  {
    return ordering;
  }

  @Override
  public void close()
  {
    CloseableUtils.closeAndWrapExceptions(resourcesSupplier.get());
  }


  private VectorColumnSelectorFactory makeVectorColumnSelectorFactoryForOffset(
      ColumnCache columnCache,
      VectorOffset baseOffset
  )
  {
    return new QueryableIndexVectorColumnSelectorFactory(
        index,
        baseOffset,
        columnCache,
        virtualColumns
    );
  }

  /**
   * Search the time column using binary search. Benchmarks on various other approaches (linear search, binary
   * search that switches to linear at various closeness thresholds) indicated that a pure binary search worked best.
   *
   * @param timeColumn the column
   * @param timestamp  the timestamp to search for
   * @param startIndex first index to search, inclusive
   * @param endIndex   last index to search, exclusive
   * @return first index that has a timestamp equal to, or greater, than "timestamp"
   */
  @VisibleForTesting
  static int timeSearch(
      final NumericColumn timeColumn,
      final long timestamp,
      final int startIndex,
      final int endIndex
  )
  {
    final long prevTimestamp = timestamp - 1;

    // Binary search for prevTimestamp.
    int minIndex = startIndex;
    int maxIndex = endIndex - 1;

    while (minIndex <= maxIndex) {
      final int currIndex = (minIndex + maxIndex) >>> 1;
      final long currValue = timeColumn.getLongSingleValueRow(currIndex);

      if (currValue < prevTimestamp) {
        minIndex = currIndex + 1;
      } else if (currValue > prevTimestamp) {
        maxIndex = currIndex - 1;
      } else {
        // The value at currIndex is prevTimestamp.
        minIndex = currIndex;
        break;
      }
    }

    // Do linear search for the actual timestamp, then return.
    for (; minIndex < endIndex; minIndex++) {
      final long currValue = timeColumn.getLongSingleValueRow(minIndex);
      if (currValue >= timestamp) {
        return minIndex;
      }
    }

    // Not found.
    return endIndex;
  }

  private static class QueryableIndexVectorCursor implements VectorCursor
  {
    private final int vectorSize;
    private final VectorOffset offset;
    private final VectorColumnSelectorFactory columnSelectorFactory;

    public QueryableIndexVectorCursor(
        final VectorColumnSelectorFactory vectorColumnSelectorFactory,
        final VectorOffset offset,
        final int vectorSize
    )
    {
      this.columnSelectorFactory = vectorColumnSelectorFactory;
      this.vectorSize = vectorSize;
      this.offset = offset;
    }

    @Override
    public int getMaxVectorSize()
    {
      return vectorSize;
    }

    @Override
    public int getCurrentVectorSize()
    {
      return offset.getCurrentVectorSize();
    }

    @Override
    public VectorColumnSelectorFactory getColumnSelectorFactory()
    {
      return columnSelectorFactory;
    }

    @Override
    public void advance()
    {
      offset.advance();
      BaseQuery.checkInterrupted();
    }

    @Override
    public boolean isDone()
    {
      return offset.isDone();
    }

    @Override
    public void reset()
    {
      offset.reset();
    }
  }

  private static class QueryableIndexCursor implements HistoricalCursor
  {
    private final Offset cursorOffset;
    private final ColumnSelectorFactory columnSelectorFactory;

    QueryableIndexCursor(Offset cursorOffset, ColumnSelectorFactory columnSelectorFactory)
    {
      this.cursorOffset = cursorOffset;
      this.columnSelectorFactory = columnSelectorFactory;
    }

    @Override
    public Offset getOffset()
    {
      return cursorOffset;
    }

    @Override
    public ColumnSelectorFactory getColumnSelectorFactory()
    {
      return columnSelectorFactory;
    }

    @Override
    public void advance()
    {
      cursorOffset.increment();
      // Must call BaseQuery.checkInterrupted() after cursorOffset.increment(), not before, because
      // FilteredOffset.increment() is a potentially long, not an "instant" operation (unlike to all other subclasses
      // of Offset) and it returns early on interruption, leaving itself in an illegal state.  We should not let
      // aggregators, etc. access this illegal state and throw a QueryInterruptedException by calling
      // BaseQuery.checkInterrupted().
      BaseQuery.checkInterrupted();
    }

    @Override
    public void advanceUninterruptibly()
    {
      cursorOffset.increment();
    }

    @Override
    public boolean isDone()
    {
      return !cursorOffset.withinBounds();
    }

    @Override
    public boolean isDoneOrInterrupted()
    {
      return isDone() || Thread.currentThread().isInterrupted();
    }

    @Override
    public void reset()
    {
      cursorOffset.reset();
    }
  }

  public abstract static class TimestampCheckingOffset extends Offset
  {
    final Offset baseOffset;
    final NumericColumn timestamps;
    final long timeLimit;
    final boolean allWithinThreshold;

    TimestampCheckingOffset(
        Offset baseOffset,
        NumericColumn timestamps,
        long timeLimit,
        boolean allWithinThreshold
    )
    {
      this.baseOffset = baseOffset;
      this.timestamps = timestamps;
      this.timeLimit = timeLimit;
      // checks if all the values are within the Threshold specified, skips timestamp lookups and checks if all values are within threshold.
      this.allWithinThreshold = allWithinThreshold;
    }

    @Override
    public int getOffset()
    {
      return baseOffset.getOffset();
    }

    @Override
    public boolean withinBounds()
    {
      if (!baseOffset.withinBounds()) {
        return false;
      }
      if (allWithinThreshold) {
        return true;
      }
      return timeInRange(timestamps.getLongSingleValueRow(baseOffset.getOffset()));
    }

    @Override
    public void reset()
    {
      baseOffset.reset();
    }

    @Override
    public ReadableOffset getBaseReadableOffset()
    {
      return baseOffset.getBaseReadableOffset();
    }

    protected abstract boolean timeInRange(long current);

    @Override
    public void increment()
    {
      baseOffset.increment();
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public Offset clone()
    {
      throw new IllegalStateException("clone");
    }

    @Override
    public void inspectRuntimeShape(RuntimeShapeInspector inspector)
    {
      inspector.visit("baseOffset", baseOffset);
      inspector.visit("timestamps", timestamps);
      inspector.visit("allWithinThreshold", allWithinThreshold);
    }
  }

  public static class AscendingTimestampCheckingOffset extends TimestampCheckingOffset
  {
    AscendingTimestampCheckingOffset(
        Offset baseOffset,
        NumericColumn timestamps,
        long timeLimit,
        boolean allWithinThreshold
    )
    {
      super(baseOffset, timestamps, timeLimit, allWithinThreshold);
    }

    @Override
    protected final boolean timeInRange(long current)
    {
      return current < timeLimit;
    }

    @Override
    public String toString()
    {
      return (baseOffset.withinBounds() ? timestamps.getLongSingleValueRow(baseOffset.getOffset()) : "OOB") +
             "<" + timeLimit + "::" + baseOffset;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public Offset clone()
    {
      return new AscendingTimestampCheckingOffset(baseOffset.clone(), timestamps, timeLimit, allWithinThreshold);
    }
  }

  public static class DescendingTimestampCheckingOffset extends TimestampCheckingOffset
  {
    DescendingTimestampCheckingOffset(
        Offset baseOffset,
        NumericColumn timestamps,
        long timeLimit,
        boolean allWithinThreshold
    )
    {
      super(baseOffset, timestamps, timeLimit, allWithinThreshold);
    }

    @Override
    protected final boolean timeInRange(long current)
    {
      return current >= timeLimit;
    }

    @Override
    public String toString()
    {
      return timeLimit + ">=" +
             (baseOffset.withinBounds() ? timestamps.getLongSingleValueRow(baseOffset.getOffset()) : "OOB") +
             "::" + baseOffset;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    @Override
    public Offset clone()
    {
      return new DescendingTimestampCheckingOffset(baseOffset.clone(), timestamps, timeLimit, allWithinThreshold);
    }
  }

  private static final class CursorResources implements Closeable
  {
    private final Closer closer;
    private final long minDataTimestamp;
    private final long maxDataTimestamp;
    private final int numRows;
    @Nullable
    private final Filter filter;
    @Nullable
    private final FilterBundle filterBundle;
    private final NumericColumn timestamps;
    private final ColumnCache columnCache;
    @Nullable
    private final QueryMetrics<? extends Query<?>> metrics;

    private CursorResources(
        QueryableIndex index,
        VirtualColumns virtualColumns,
        @Nullable Filter filter,
        @Nullable QueryMetrics<? extends Query<?>> metrics
    )
    {
      this.closer = Closer.create();
      this.filter = filter;
      this.metrics = metrics;
      this.columnCache = new ColumnCache(index, closer);
      final ColumnSelectorColumnIndexSelector bitmapIndexSelector = new ColumnSelectorColumnIndexSelector(
          index.getBitmapFactoryForDimensions(),
          virtualColumns,
          columnCache
      );
      try {
        this.numRows = index.getNumRows();
        this.filterBundle = makeFilterBundle(bitmapIndexSelector, numRows);
        this.timestamps = (NumericColumn) columnCache.getColumn(ColumnHolder.TIME_COLUMN_NAME);
        this.minDataTimestamp = DateTimes.utc(timestamps.getLongSingleValueRow(0)).getMillis();
        this.maxDataTimestamp = DateTimes.utc(timestamps.getLongSingleValueRow(timestamps.length() - 1)).getMillis();
      }
      catch (Throwable t) {
        throw CloseableUtils.closeAndWrapInCatch(t, closer);
      }
    }

    @Override
    public void close() throws IOException
    {
      closer.close();
    }

    @Nullable
    private FilterBundle makeFilterBundle(
        ColumnSelectorColumnIndexSelector bitmapIndexSelector,
        int numRows
    )
    {
      final BitmapFactory bitmapFactory = bitmapIndexSelector.getBitmapFactory();
      final BitmapResultFactory<?> bitmapResultFactory;
      if (metrics != null) {
        bitmapResultFactory = metrics.makeBitmapResultFactory(bitmapFactory);
        metrics.reportSegmentRows(numRows);
      } else {
        bitmapResultFactory = new DefaultBitmapResultFactory(bitmapFactory);
      }
      if (filter == null) {
        return null;
      }
      final long bitmapConstructionStartNs = System.nanoTime();
      final FilterBundle filterBundle = filter.makeFilterBundle(
          bitmapIndexSelector,
          bitmapResultFactory,
          numRows,
          numRows,
          false
      );
      if (metrics != null) {
        final long buildTime = System.nanoTime() - bitmapConstructionStartNs;
        metrics.reportBitmapConstructionTime(buildTime);
        final FilterBundle.BundleInfo info = filterBundle.getInfo();
        metrics.filterBundle(info);
        log.debug("Filter partitioning (%sms):%s", TimeUnit.NANOSECONDS.toMillis(buildTime), info);
        if (filterBundle.getIndex() != null) {
          metrics.reportPreFilteredRows(filterBundle.getIndex().getBitmap().size());
        } else {
          metrics.reportPreFilteredRows(0);
        }
      } else if (log.isDebugEnabled()) {
        final FilterBundle.BundleInfo info = filterBundle.getInfo();
        final long buildTime = System.nanoTime() - bitmapConstructionStartNs;
        log.debug("Filter partitioning (%sms):%s", TimeUnit.NANOSECONDS.toMillis(buildTime), info);
      }
      return filterBundle;
    }
  }
}

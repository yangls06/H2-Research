/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import org.h2.constant.ErrorCode;
import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.index.BaseIndex;
import org.h2.index.Cursor;
import org.h2.index.IndexType;
import org.h2.message.DbException;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.db.TransactionStore.Transaction;
import org.h2.mvstore.db.TransactionStore.TransactionMap;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.util.New;
import org.h2.value.Value;
import org.h2.value.ValueArray;
import org.h2.value.ValueLong;
import org.h2.value.ValueNull;

/**
 * A table stored in a MVStore.
 */
public class MVSecondaryIndex extends BaseIndex {

    /**
     * The multi-value table.
     */
    final MVTable mvTable;

    private final int keyColumns;
    private String mapName;
    private TransactionMap<Value, Value> dataMap;

    public MVSecondaryIndex(Database db, MVTable table, int id, String indexName,
                IndexColumn[] columns, IndexType indexType) {
        this.mvTable = table;
        initBaseIndex(table, id, indexName, columns, indexType);
        if (!database.isStarting()) {
            checkIndexColumnTypes(columns); //不能在CLOB、BLOG列上建索引
        }
        // always store the row key in the map key,
        // even for unique indexes, as some of the index columns could be null
        keyColumns = columns.length + 1; //比MVPrimaryIndex多加了一列，最后一列对应行key
        int[] sortTypes = new int[keyColumns];
        for (int i = 0; i < columns.length; i++) {
            sortTypes[i] = columns[i].sortType;
        }
        sortTypes[keyColumns - 1] = SortOrder.ASCENDING;
        mapName = getName() + "_" + getId();
        //keyType和valueType与MVPrimaryIndex刚好相反，MVPrimaryIndex的keyType是new ValueDataType(null, null, null)
        ValueDataType keyType = new ValueDataType(
                db.getCompareMode(), db, sortTypes);
        ValueDataType valueType = new ValueDataType(null, null, null);
        MVMap.Builder<Value, Value> mapBuilder = new MVMap.Builder<Value, Value>().
                keyType(keyType).
                valueType(valueType);
        dataMap = mvTable.getTransaction(null).openMap(mapName, mapBuilder);
    }

    private static void checkIndexColumnTypes(IndexColumn[] columns) {
        for (IndexColumn c : columns) {
            int type = c.column.getType();
            if (type == Value.CLOB || type == Value.BLOB) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, "Index on BLOB or CLOB column: " + c.column.getCreateSQL());
            }
        }
    }

    @Override
    public void close(Session session) {
        // ok
    }
    
    ////MVPrimaryIndex没有覆盖rename方法，因为MVPrimaryIndex没有Create SQL，而MVSecondaryIndex有Create SQL，
    //有Create SQL说明就可以对索引得命名
    public void rename(String newName) {
        TransactionMap<Value, Value> map = getMap(null);
        String newMapName = newName + "_" + getId();
        map.renameMap(newMapName);
        mapName = newMapName;
        super.rename(newName);
    }

    @Override
    public void add(Session session, Row row) {
        TransactionMap<Value, Value> map = getMap(session);
        ValueArray array = getKey(row); //把所有索引列和行key组合成map的key
        if (indexType.isUnique()) {
            array.getList()[keyColumns - 1] = ValueLong.get(0); //如果是唯一索引，把行key清0
            ValueArray key = (ValueArray) map.ceilingKey(array);
            if (key != null) {
                SearchRow r2 = getRow(key.getList());
                if (compareRows(row, r2) == 0) {
                    if (!containsNullAndAllowMultipleNull(r2)) {
                        throw getDuplicateKeyException();
                    }
                }
            }
        }
        array.getList()[keyColumns - 1] = ValueLong.get(row.getKey()); //在唯一索引时会改变为0，所以这里重新设置回来
        map.put(array, ValueLong.get(0)); //值都是0
    }

    @Override
    public void remove(Session session, Row row) {
        ValueArray array = getKey(row); //把所有索引列和行key组合成map的key
        TransactionMap<Value, Value> map = getMap(session);
        map.setSavepoint(map.getTransaction().setSavepoint());
        Value old = map.remove(array);
        if (old == null) {
            if (old == null) { //为什么要比较两次？
                throw DbException.get(ErrorCode.ROW_NOT_FOUND_WHEN_DELETING_1,
                        getSQL() + ": " + row.getKey());
            }
        }
    }

    @Override
    public Cursor find(Session session, SearchRow first, SearchRow last) {
        Value min = getKey(first);
        TransactionMap<Value, Value> map = getMap(session);
        map.setSavepoint(map.getTransaction().setSavepoint());
        return new MVStoreCursor(session, map.keyIterator(min), last);
    }

    private ValueArray getKey(SearchRow r) { //把key也放到ValueArray最后了
        if (r == null) {
            return null;
        }
        Value[] array = new Value[keyColumns];
        for (int i = 0; i < columns.length; i++) {
            Column c = columns[i];
            int idx = c.getColumnId();
            if (r != null) {
                array[i] = r.getValue(idx);
            }
        }
        array[keyColumns - 1] = ValueLong.get(r.getKey());
        return ValueArray.get(array);
    }

    /**
     * Get the row with the given index key.
     *
     * @param array the index key
     * @return the row
     */
    SearchRow getRow(Value[] array) {
        SearchRow searchRow = mvTable.getTemplateRow();
        searchRow.setKey((array[array.length - 1]).getLong());
        Column[] cols = getColumns();
        for (int i = 0; i < array.length - 1; i++) {
            Column c = cols[i];
            int idx = c.getColumnId();
            Value v = array[i];
            searchRow.setValue(idx, v);
        }
        return searchRow;
    }

    public MVTable getTable() {
        return mvTable;
    }

    @Override
    public double getCost(Session session, int[] masks, SortOrder sortOrder) {
        return 10 * getCostRangeIndex(masks, dataMap.map.getSize(), sortOrder);
    }

    @Override
    public void remove(Session session) {
        TransactionMap<Value, Value> map = getMap(session);
        if (!map.isClosed()) {
            map.removeMap();
        }
    }

    @Override
    public void truncate(Session session) {
        TransactionMap<Value, Value> map = getMap(session);
        map.clear();
    }

    @Override
    public boolean canGetFirstOrLast() {
        return true;
    }

    @Override
    public Cursor findFirstOrLast(Session session, boolean first) {
        TransactionMap<Value, Value> map = getMap(session);
        Value key = first ? map.firstKey() : map.lastKey();
        while (true) {
            if (key == null) {
                return new MVStoreCursor(session, Collections.<Value>emptyList().iterator(), null);
            }
            if (((ValueArray) key).getList()[0] != ValueNull.INSTANCE) {
                break;
            }
            key = first ? map.higherKey(key) : map.lowerKey(key);
        }
        ArrayList<Value> list = New.arrayList();
        list.add(key);
        MVStoreCursor cursor = new MVStoreCursor(session, list.iterator(), null);
        cursor.next();
        return cursor;
    }

    @Override
    public boolean needRebuild() {
        return dataMap.map.getSize() == 0; //Map中没有记录时才要重建
    }

    @Override
    public long getRowCount(Session session) {
    	//getMap(session)与getMap(null)的差别是，getMap(session)会以当前事务的savepoint为准，
    	//而getMap(null)没有限制，因为getRowCountApproximation()是求近似值，所以用getMap(null)合适
    	//getMap(null)得到的值有可能大于getMap(session)
        TransactionMap<Value, Value> map = getMap(session);
        return map.getSize();
    }

    @Override
    public long getRowCountApproximation() {
        return dataMap.map.getSize();
    }

    public long getDiskSpaceUsed() {
        // TODO estimate disk space usage
        return 0;
    }

    @Override
    public void checkRename() {
        // ok
    }
    
    /**
     * Get the map to store the data.
     *
     * @param session the session
     * @return the map
     */
    TransactionMap<Value, Value> getMap(Session session) { //与MVPrimaryIndex中的一样
        if (session == null) {
            return dataMap;
        }
        Transaction t = mvTable.getTransaction(session);
        long savepoint = session.getStatementSavepoint();
        return dataMap.getInstance(t, savepoint);
    }

    /**
     * A cursor.
     */
    class MVStoreCursor implements Cursor {

        private final Session session;
        private final Iterator<Value> it;
        private final SearchRow last;
        private Value current;
        private SearchRow searchRow;
        private Row row;
        
        //与MVPrimaryIndex.MVStoreCursor不同，这里的Iterator<Value> it就是Key值，但是因为Key值已经完整包括索引列值和行key了，
        //所以不需要再从Map中get值了
        public MVStoreCursor(Session session, Iterator<Value> it, SearchRow last) {
            this.session = session;
            this.it = it;
            this.last = last;
        }

        @Override
        public Row get() {
            if (row == null) {
                SearchRow r = getSearchRow();
                if (r != null) {
                    row = mvTable.getRow(session, r.getKey());
                }
            }
            return row;
        }

        @Override
        public SearchRow getSearchRow() {
            if (searchRow == null) {
                if (current != null) {
                    searchRow = getRow(((ValueArray) current).getList());
                }
            }
            return searchRow;
        }

        @Override
        public boolean next() {
            current = it.next();
            searchRow = null;
            if (current != null) {
                if (last != null && compareRows(getSearchRow(), last) > 0) {
                    searchRow = null;
                    current = null;
                }
            }
            row = null;
            return current != null;
        }

        @Override
        public boolean previous() {
            // TODO previous
            return false;
        }

    }

}

/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.fulltext;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.document.DateTools;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Searcher;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;
import org.h2.api.Trigger;
import org.h2.command.Parser;
import org.h2.engine.Session;
import org.h2.expression.ExpressionColumn;
import org.h2.jdbc.JdbcConnection;
import org.h2.store.fs.FileUtils;
import org.h2.tools.SimpleResultSet;
import org.h2.util.JdbcUtils;
import org.h2.util.New;
import org.h2.util.StatementBuilder;
import org.h2.util.StringUtils;
import org.h2.util.Utils;
/*## LUCENE2 ##
 import org.apache.lucene.index.IndexModifier;
 import org.apache.lucene.search.Hits;
 //*/
//## LUCENE3 ##

//*/

/**
 * This class implements the full text search based on Apache Lucene. Most
 * methods can be called using SQL statements as well.
 */
public class FullTextLuceneEx extends FullText {

	/** Whether the text content should be stored in the Lucene index. */
	protected static final boolean STORE_DOCUMENT_TEXT_IN_INDEX = Utils.getProperty("h2.storeDocumentTextInIndex",
			false);

	/** Insert Trigger execute commit */
	protected static boolean TRIGGER_COMMIT = true;

	/** Use RAMDirectory */
	protected static boolean USE_RAM_DIRECTORY = false;

	/** Lucene version */
	protected static Version LUCENE_VERSION = getVersion();

	/** Analyzer class */
	protected static Analyzer ANALYZER = getAnalyzer();

	/** get Lucene version */
	private static Version getVersion() {
		return Version.valueOf("LUCENE_36");
		
//		return Version.valueOf("LUCENE_" + Utils.getProperty("h2.luceneVersion", "35"));
	}

	/** get Analyzer instance */
	private static Analyzer getAnalyzer() {
		return new CJKAnalyzer(LUCENE_VERSION);
		
//		String className = Utils.getProperty("h2.luceneAnalyzer", null);
//		try {
//			if (className != null) {
//				Constructor<Analyzer> constructor = (Constructor<Analyzer>) Class.forName(className).getConstructor(
//						Version.class);
//				return constructor.newInstance(LUCENE_VERSION);
//			}
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
//		return new StandardAnalyzer(LUCENE_VERSION);
	}

	private static final HashMap<String, IndexAccess> INDEX_ACCESS = New.hashMap();
	private static final String TRIGGER_PREFIX = "FTL_";
	private static final String SCHEMA = "FTL";
	private static final String LUCENE_FIELD_DATA = "_DATA";
	private static final String LUCENE_FIELD_QUERY = "_QUERY";
	private static final String LUCENE_FIELD_MODIFIED = "_modified";
	private static final String LUCENE_FIELD_COLUMN_PREFIX = "_";

	/**
	 * Initializes full text search functionality for this database. This adds
	 * the following Java functions to the database:
	 * <ul>
	 * <li>FTL_CREATE_INDEX(schemaNameString, tableNameString, columnListString)
	 * </li>
	 * <li>FTL_SEARCH(queryString, limitInt, offsetInt): result set</li>
	 * <li>FTL_REINDEX()</li>
	 * <li>FTL_DROP_ALL()</li>
	 * </ul>
	 * It also adds a schema FTL to the database where bookkeeping information
	 * is stored. This function may be called from a Java application, or by
	 * using the SQL statements:
	 * 
	 * <pre>
	 * CREATE ALIAS IF NOT EXISTS FTL_INIT FOR
	 *      &quot;org.h2.fulltext.FullTextLuceneEx.init&quot;;
	 * CALL FTL_INIT();
	 * </pre>
	 * 
	 * @param conn the connection
	 */
	public static void init(Connection conn) throws SQLException {

		TRIGGER_COMMIT = Utils.getProperty("h2.isTriggerCommit", true);
		USE_RAM_DIRECTORY = Utils.getProperty("h2.useRamDirectory", false);
		LUCENE_VERSION = getVersion();
		ANALYZER = getAnalyzer();

		Statement stat = conn.createStatement();
		stat.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
		stat.execute("CREATE TABLE IF NOT EXISTS " + SCHEMA
				+ ".INDEXES(SCHEMA VARCHAR, TABLE VARCHAR, COLUMNS VARCHAR, PRIMARY KEY(SCHEMA, TABLE))");
		stat.execute("CREATE ALIAS IF NOT EXISTS FTL_CREATE_INDEX FOR \"" + FullTextLuceneEx.class.getName()
				+ ".createIndex\"");
		stat.execute("CREATE ALIAS IF NOT EXISTS FTL_SEARCH FOR \"" + FullTextLuceneEx.class.getName() + ".search\"");
		stat.execute("CREATE ALIAS IF NOT EXISTS FTL_SEARCH_DATA FOR \"" + FullTextLuceneEx.class.getName()
				+ ".searchData\"");
		stat.execute("CREATE ALIAS IF NOT EXISTS FTL_REINDEX FOR \"" + FullTextLuceneEx.class.getName() + ".reindex\"");
		stat.execute("CREATE ALIAS IF NOT EXISTS FTL_DROP_ALL FOR \"" + FullTextLuceneEx.class.getName() + ".dropAll\"");
		stat.execute("CREATE ALIAS IF NOT EXISTS FTL_FLUSH_RAM FOR \"" + FullTextLuceneEx.class.getName()
				+ ".flushRam\"");
		stat.execute("CREATE ALIAS IF NOT EXISTS FTL_COMMIT FOR \"" + FullTextLuceneEx.class.getName() + ".commit\"");
		stat.execute("CREATE ALIAS IF NOT EXISTS FTL_COMMIT_ALL FOR \"" + FullTextLuceneEx.class.getName()
				+ ".commitAll\"");
		try {
			getIndexAccess(conn);
		} catch (SQLException e) {
			throw convertException(e);
		}
	}

	/**
	 * if use RAMDirectory, store index to FSDirectory
	 * 
	 * @param conn
	 * @throws SQLException
	 * @throws IOException
	 */
	public static void flushRam(Connection conn) throws SQLException, IOException {
		IndexAccess access = getIndexAccess(conn);
		if (access.writer.getDirectory() instanceof RAMDirectory) {
			Directory.copy(access.writer.getDirectory(), FSDirectory.open(new File(getIndexPath(conn))), false);
		}
	}

	/**
	 * commit Lucene index
	 * 
	 * @param conn
	 * @throws SQLException
	 * @throws IOException
	 */
	public static void commit(Connection conn) throws SQLException, IOException {
		IndexAccess indexAccess = getIndexAccess(conn);
		indexAccess.writer.commit();
		// recreate Searcher with the IndexWriter's reader.
		indexAccess.searcher.close();
		indexAccess.reader.close();
		IndexReader reader = indexAccess.writer.getReader();
		indexAccess.reader = reader;
		indexAccess.searcher = new IndexSearcher(reader);
	}

	/**
	 * commit Lucene index and connection
	 * 
	 * @param conn
	 * @throws SQLException
	 * @throws IOException
	 */
	public static void commitAll(Connection conn) throws SQLException, IOException {
		commit(conn);
		conn.commit();
	}

	/**
	 * Create a new full text index for a table and column list. Each table may
	 * only have one index at any time.
	 * 
	 * @param conn the connection
	 * @param schema the schema name of the table (case sensitive)
	 * @param table the table name (case sensitive)
	 * @param columnList the column list (null for all columns)
	 */
	public static void createIndex(Connection conn, String schema, String table, String columnList) throws SQLException {
		init(conn);
		PreparedStatement prep = conn.prepareStatement("INSERT INTO " + SCHEMA
				+ ".INDEXES(SCHEMA, TABLE, COLUMNS) VALUES(?, ?, ?)");
		prep.setString(1, schema);
		prep.setString(2, table);
		prep.setString(3, columnList);
		prep.execute();
		createTrigger(conn, schema, table);
		indexExistingRows(conn, schema, table);
	}

	/**
	 * Re-creates the full text index for this database. Calling this method is
	 * usually not needed, as the index is kept up-to-date automatically.
	 * 
	 * @param conn the connection
	 */
	public static void reindex(Connection conn) throws SQLException {
		init(conn);
		removeAllTriggers(conn, TRIGGER_PREFIX);
		removeIndexFiles(conn);
		Statement stat = conn.createStatement();
		ResultSet rs = stat.executeQuery("SELECT * FROM " + SCHEMA + ".INDEXES");
		while (rs.next()) {
			String schema = rs.getString("SCHEMA");
			String table = rs.getString("TABLE");
			createTrigger(conn, schema, table);
			indexExistingRows(conn, schema, table);
		}
	}

	/**
	 * Drops all full text indexes from the database.
	 * 
	 * @param conn the connection
	 */
	public static void dropAll(Connection conn) throws SQLException {
		Statement stat = conn.createStatement();
		stat.execute("DROP SCHEMA IF EXISTS " + SCHEMA);
		removeAllTriggers(conn, TRIGGER_PREFIX);
		removeIndexFiles(conn);
	}

	/**
	 * Searches from the full text index for this database. The returned result
	 * set has the following column:
	 * <ul>
	 * <li>QUERY (varchar): the query to use to get the data. The query does not
	 * include 'SELECT * FROM '. Example: PUBLIC.TEST WHERE ID = 1</li>
	 * <li>SCORE (float) the relevance score as returned by Lucene.</li>
	 * </ul>
	 * 
	 * @param conn the connection
	 * @param text the search query
	 * @param limit the maximum number of rows or 0 for no limit
	 * @param offset the offset or 0 for no offset
	 * @return the result set
	 */
	public static ResultSet search(Connection conn, String text, int limit, int offset) throws SQLException {
		return search(conn, text, limit, offset, false);
	}

	/**
	 * Searches from the full text index for this database. The result contains
	 * the primary key data as an array. The returned result set has the
	 * following columns:
	 * <ul>
	 * <li>SCHEMA (varchar): the schema name. Example: PUBLIC</li>
	 * <li>TABLE (varchar): the table name. Example: TEST</li>
	 * <li>COLUMNS (array of varchar): comma separated list of quoted column
	 * names. The column names are quoted if necessary. Example: (ID)</li>
	 * <li>KEYS (array of values): comma separated list of values. Example: (1)</li>
	 * <li>SCORE (float) the relevance score as returned by Lucene.</li>
	 * </ul>
	 * 
	 * @param conn the connection
	 * @param text the search query
	 * @param limit the maximum number of rows or 0 for no limit
	 * @param offset the offset or 0 for no offset
	 * @return the result set
	 */
	public static ResultSet searchData(Connection conn, String text, int limit, int offset) throws SQLException {
		return search(conn, text, limit, offset, true);
	}

	/**
	 * Convert an exception to a fulltext exception.
	 * 
	 * @param e the original exception
	 * @return the converted SQL exception
	 */
	protected static SQLException convertException(Exception e) {
		SQLException e2 = new SQLException("Error while indexing document", "FULLTEXT");
		e2.initCause(e);
		return e2;
	}

	/**
	 * Create the trigger.
	 * 
	 * @param conn the database connection
	 * @param schema the schema name
	 * @param table the table name
	 */
	protected static void createTrigger(Connection conn, String schema, String table) throws SQLException {
		Statement stat = conn.createStatement();
		String trigger = StringUtils.quoteIdentifier(schema) + "."
				+ StringUtils.quoteIdentifier(TRIGGER_PREFIX + table);
		stat.execute("DROP TRIGGER IF EXISTS " + trigger);
		StringBuilder buff = new StringBuilder("CREATE TRIGGER IF NOT EXISTS ");
		// the trigger is also called on rollback because transaction rollback
		// will not undo the changes in the Lucene index
		buff.append(trigger).append(" AFTER INSERT, UPDATE, DELETE, ROLLBACK ON ")
				.append(StringUtils.quoteIdentifier(schema)).append('.').append(StringUtils.quoteIdentifier(table))
				.append(" FOR EACH ROW CALL \"").append(FullTextLuceneEx.FullTextTrigger.class.getName()).append('\"');
		stat.execute(buff.toString());
	}

	/**
	 * Get the index writer/searcher wrapper for the given connection.
	 * 
	 * @param conn the connection
	 * @return the index access wrapper
	 */
	protected static IndexAccess getIndexAccess(Connection conn) throws SQLException {
		String path = getIndexPath(conn);
		synchronized (INDEX_ACCESS) {
			IndexAccess access = INDEX_ACCESS.get(path);
			if (access == null) {
				try {
					/*
					 * ## LUCENE2 ## boolean recreate =
					 * !IndexReader.indexExists(path); Analyzer analyzer = new
					 * StandardAnalyzer(); access = new IndexAccess();
					 * access.modifier = new IndexModifier(path, analyzer,
					 * recreate); //
					 */
					// ## LUCENE3 ##
					File f = new File(path);
					// Directory indexDir = FSDirectory.open(f);
					Directory indexDir = null;
					if (USE_RAM_DIRECTORY) {
						if (f.exists())
							indexDir = new RAMDirectory(FSDirectory.open(f));
						else
							indexDir = new RAMDirectory();
					} else {
						indexDir = FSDirectory.open(f);
					}
					boolean recreate = !IndexReader.indexExists(indexDir);
					IndexWriter writer = new IndexWriter(indexDir, ANALYZER, recreate,
							IndexWriter.MaxFieldLength.UNLIMITED);
					// see http://wiki.apache.org/lucene-java/NearRealtimeSearch
					IndexReader reader = writer.getReader();
					access = new IndexAccess();
					access.writer = writer;
					access.reader = reader;
					access.searcher = new IndexSearcher(reader);
					// */
				} catch (IOException e) {
					throw convertException(e);
				}
				INDEX_ACCESS.put(path, access);
			}
			return access;
		}
	}

	/**
	 * Get the path of the Lucene index for this database.
	 * 
	 * @param conn the database connection
	 * @return the path
	 */
	protected static String getIndexPath(Connection conn) throws SQLException {
		Statement stat = conn.createStatement();
		ResultSet rs = stat.executeQuery("CALL DATABASE_PATH()");
		rs.next();
		String path = rs.getString(1);
		if (path == null) {
			throw throwException("Fulltext search for in-memory databases is not supported.");
		}
		int index = path.lastIndexOf(':');
		// position 1 means a windows drive letter is used, ignore that
		if (index > 1) {
			path = path.substring(index + 1);
		}
		rs.close();
		return path + "_" + ANALYZER.getClass().getName();
	}

	/**
	 * Add the existing data to the index.
	 * 
	 * @param conn the database connection
	 * @param schema the schema name
	 * @param table the table name
	 */
	protected static void indexExistingRows(Connection conn, String schema, String table) throws SQLException {
		FullTextLuceneEx.FullTextTrigger existing = new FullTextLuceneEx.FullTextTrigger();
		existing.init(conn, schema, null, table, false, Trigger.INSERT);
		String sql = "SELECT * FROM " + StringUtils.quoteIdentifier(schema) + "." + StringUtils.quoteIdentifier(table);
		ResultSet rs = conn.createStatement().executeQuery(sql);
		int columnCount = rs.getMetaData().getColumnCount();
		while (rs.next()) {
			Object[] row = new Object[columnCount];
			for (int i = 0; i < columnCount; i++) {
				row[i] = rs.getObject(i + 1);
			}
			existing.insert(row, false);
		}
		existing.commitIndex();
	}

	private static void removeIndexFiles(Connection conn) throws SQLException {
		String path = getIndexPath(conn);
		IndexAccess access = INDEX_ACCESS.get(path);
		if (access != null) {
			removeIndexAccess(access, path);
		}
		FileUtils.deleteRecursive(path, false);
	}

	/**
	 * Close the index writer and searcher and remove them from the index access
	 * set.
	 * 
	 * @param access the index writer/searcher wrapper
	 * @param indexPath the index path
	 */
	protected static void removeIndexAccess(IndexAccess access, String indexPath) throws SQLException {
		synchronized (INDEX_ACCESS) {
			try {
				INDEX_ACCESS.remove(indexPath);
				/*
				 * ## LUCENE2 ## access.modifier.flush();
				 * access.modifier.close(); //
				 */
				// ## LUCENE3 ##
				access.searcher.close();
				access.reader.close();
				access.writer.close();
				// */
			} catch (Exception e) {
				throw convertException(e);
			}
		}
	}

	/**
	 * Do the search.
	 * 
	 * @param conn the database connection
	 * @param text the query
	 * @param limit the limit
	 * @param offset the offset
	 * @param data whether the raw data should be returned
	 * @return the result set
	 */
	protected static ResultSet search(Connection conn, String text, int limit, int offset, boolean data)
			throws SQLException {
		SimpleResultSet result = createResultSet(data);
		if (conn.getMetaData().getURL().startsWith("jdbc:columnlist:")) {
			// this is just to query the result set columns
			return result;
		}
		if (text == null || text.trim().length() == 0) {
			return result;
		}
		try {
			IndexAccess access = getIndexAccess(conn);
			/*
			 * ## LUCENE2 ## access.modifier.flush(); String path =
			 * getIndexPath(conn); IndexReader reader = IndexReader.open(path);
			 * Analyzer analyzer = new StandardAnalyzer(); Searcher searcher =
			 * new IndexSearcher(reader); QueryParser parser = new
			 * QueryParser(LUCENE_FIELD_DATA, analyzer); Query query =
			 * parser.parse(text); Hits hits = searcher.search(query); int max =
			 * hits.length(); if (limit == 0) { limit = max; } for (int i = 0; i
			 * < limit && i + offset < max; i++) { Document doc = hits.doc(i +
			 * offset); float score = hits.score(i + offset); //
			 */
			// ## LUCENE3 ##
			// take a reference as the searcher may change
			Searcher searcher = access.searcher;
			// reuse the same analyzer; it's thread-safe;
			// also allows subclasses to control the analyzer used.
			Analyzer analyzer = access.writer.getAnalyzer();
			QueryParser parser = new QueryParser(LUCENE_VERSION, LUCENE_FIELD_DATA, analyzer);
			parser.setAutoGeneratePhraseQueries(true);
			Query query = parser.parse(text);
			// Lucene 3 insists on a hard limit and will not provide
			// a total hits value. Take at least 100 which is
			// an optimal limit for Lucene as any more
			// will trigger writing results to disk.
			int maxResults = (limit == 0 ? 100 : limit) + offset;
			TopDocs docs = searcher.search(query, maxResults);
			if (limit == 0) {
				limit = docs.totalHits;
			}
			for (int i = 0, len = docs.scoreDocs.length; i < limit && i + offset < docs.totalHits && i + offset < len; i++) {
				ScoreDoc sd = docs.scoreDocs[i + offset];
				Document doc = searcher.doc(sd.doc);
				float score = sd.score;
				// */
				String q = doc.get(LUCENE_FIELD_QUERY);
				if (data) {
					int idx = q.indexOf(" WHERE ");
					JdbcConnection c = (JdbcConnection) conn;
					Session session = (Session) c.getSession();
					Parser p = new Parser(session);
					String tab = q.substring(0, idx);
					ExpressionColumn expr = (ExpressionColumn) p.parseExpression(tab);
					String schemaName = expr.getOriginalTableAliasName();
					String tableName = expr.getColumnName();
					q = q.substring(idx + " WHERE ".length());
					Object[][] columnData = parseKey(conn, q);
					result.addRow(schemaName, tableName, columnData[0], columnData[1], score);
				} else {
					result.addRow(q, score);
				}
			}
			/*
			 * ## LUCENE2 ## // TODO keep it open if possible reader.close(); //
			 */
		} catch (Exception e) {
			throw convertException(e);
		}
		return result;
	}

	/**
	 * Trigger updates the index when a inserting, updating, or deleting a row.
	 */
	public static class FullTextTrigger implements Trigger {

		protected String schema;
		protected String table;
		protected int[] keys;
		protected int[] indexColumns;
		protected String[] columns;
		protected int[] columnTypes;
		protected String indexPath;
		protected IndexAccess indexAccess;

		/**
		 * INTERNAL
		 */
		public void init(Connection conn, String schemaName, String triggerName, String tableName, boolean before,
				int type) throws SQLException {
			this.schema = schemaName;
			this.table = tableName;
			this.indexPath = getIndexPath(conn);
			this.indexAccess = getIndexAccess(conn);
			ArrayList<String> keyList = New.arrayList();
			DatabaseMetaData meta = conn.getMetaData();
			ResultSet rs = meta.getColumns(null, JdbcUtils.escapeMetaDataPattern(schemaName),
					JdbcUtils.escapeMetaDataPattern(tableName), null);
			ArrayList<String> columnList = New.arrayList();
			while (rs.next()) {
				columnList.add(rs.getString("COLUMN_NAME"));
			}
			columnTypes = new int[columnList.size()];
			columns = new String[columnList.size()];
			columnList.toArray(columns);
			rs = meta.getColumns(null, JdbcUtils.escapeMetaDataPattern(schemaName),
					JdbcUtils.escapeMetaDataPattern(tableName), null);
			for (int i = 0; rs.next(); i++) {
				columnTypes[i] = rs.getInt("DATA_TYPE");
			}
			if (keyList.size() == 0) {
				rs = meta.getPrimaryKeys(null, JdbcUtils.escapeMetaDataPattern(schemaName), tableName);
				while (rs.next()) {
					keyList.add(rs.getString("COLUMN_NAME"));
				}
			}
			if (keyList.size() == 0) {
				throw throwException("No primary key for table " + tableName);
			}
			ArrayList<String> indexList = New.arrayList();
			PreparedStatement prep = conn.prepareStatement("SELECT COLUMNS FROM " + SCHEMA
					+ ".INDEXES WHERE SCHEMA=? AND TABLE=?");
			prep.setString(1, schemaName);
			prep.setString(2, tableName);
			rs = prep.executeQuery();
			if (rs.next()) {
				String cols = rs.getString(1);
				if (cols != null) {
					for (String s : StringUtils.arraySplit(cols, ',', true)) {
						indexList.add(s);
					}
				}
			}
			if (indexList.size() == 0) {
				indexList.addAll(columnList);
			}
			keys = new int[keyList.size()];
			setColumns(keys, keyList, columnList);
			indexColumns = new int[indexList.size()];
			setColumns(indexColumns, indexList, columnList);
		}

		/**
		 * INTERNAL
		 */
		public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
			if (oldRow != null) {
				if (newRow != null) {
					// update
					if (hasChanged(oldRow, newRow, indexColumns)) {
						delete(oldRow);
						insert(newRow, TRIGGER_COMMIT);
					}
				} else {
					// delete
					delete(oldRow);
				}
			} else if (newRow != null) {
				// insert
				insert(newRow, TRIGGER_COMMIT);
			}
		}

		/**
		 * INTERNAL
		 */
		public void close() throws SQLException {
			if (indexAccess != null) {
				removeIndexAccess(indexAccess, indexPath);
				indexAccess = null;
			}
		}

		/**
		 * INTERNAL
		 */
		public void remove() {
			// ignore
		}

		/**
		 * Commit all changes to the Lucene index.
		 */
		void commitIndex() throws SQLException {
			try {
				indexAccess.writer.commit();
				// recreate Searcher with the IndexWriter's reader.
				indexAccess.searcher.close();
				indexAccess.reader.close();
				IndexReader reader = indexAccess.writer.getReader();
				indexAccess.reader = reader;
				indexAccess.searcher = new IndexSearcher(reader);
			} catch (IOException e) {
				throw convertException(e);
			}
		}

		/**
		 * Add a row to the index.
		 * 
		 * @param row the row
		 * @param commitIndex whether to commit the changes to the Lucene index
		 */
		protected void insert(Object[] row, boolean commitIndex) throws SQLException {
			/*
			 * ## LUCENE2 ## String query = getQuery(row); Document doc = new
			 * Document(); doc.add(new Field(LUCENE_FIELD_QUERY, query,
			 * Field.Store.YES, Field.Index.UN_TOKENIZED)); long time =
			 * System.currentTimeMillis(); doc.add(new
			 * Field(LUCENE_FIELD_MODIFIED, DateTools.timeToString(time,
			 * DateTools.Resolution.SECOND), Field.Store.YES,
			 * Field.Index.UN_TOKENIZED)); StatementBuilder buff = new
			 * StatementBuilder(); for (int index : indexColumns) { String
			 * columnName = columns[index]; String data = asString(row[index],
			 * columnTypes[index]); // column names that start with _ must be
			 * escaped to avoid conflicts // with internal field names (_DATA,
			 * _QUERY, _modified) if
			 * (columnName.startsWith(LUCENE_FIELD_COLUMN_PREFIX)) { columnName
			 * = LUCENE_FIELD_COLUMN_PREFIX + columnName; } doc.add(new
			 * Field(columnName, data, Field.Store.NO, Field.Index.TOKENIZED));
			 * buff.appendExceptFirst(" "); buff.append(data); } Field.Store
			 * storeText = STORE_DOCUMENT_TEXT_IN_INDEX ? Field.Store.YES :
			 * Field.Store.NO; doc.add(new Field(LUCENE_FIELD_DATA,
			 * buff.toString(), storeText, Field.Index.TOKENIZED)); try {
			 * indexAccess.modifier.addDocument(doc); } catch (IOException e) {
			 * throw convertException(e); } //
			 */
			// ## LUCENE3 ##
			String query = getQuery(row);
			Document doc = new Document();
			doc.add(new Field(LUCENE_FIELD_QUERY, query, Field.Store.YES, Field.Index.NOT_ANALYZED));
			long time = System.currentTimeMillis();
			doc.add(new Field(LUCENE_FIELD_MODIFIED, DateTools.timeToString(time, DateTools.Resolution.SECOND),
					Field.Store.YES, Field.Index.NOT_ANALYZED));
			StatementBuilder buff = new StatementBuilder();
			for (int index : indexColumns) {
				String columnName = columns[index];
				String data = asString(row[index], columnTypes[index]);
				// column names that start with _
				// must be escaped to avoid conflicts
				// with internal field names (_DATA, _QUERY, _modified)
				if (columnName.startsWith(LUCENE_FIELD_COLUMN_PREFIX)) {
					columnName = LUCENE_FIELD_COLUMN_PREFIX + columnName;
				}
				doc.add(new Field(columnName, data, Field.Store.NO, Field.Index.ANALYZED));
				buff.appendExceptFirst(" ");
				buff.append(data);
			}
			Field.Store storeText = STORE_DOCUMENT_TEXT_IN_INDEX ? Field.Store.YES : Field.Store.NO;
			doc.add(new Field(LUCENE_FIELD_DATA, buff.toString(), storeText, Field.Index.ANALYZED));
			try {
				indexAccess.writer.addDocument(doc);
				if (commitIndex) {
					indexAccess.writer.commit();
					// recreate Searcher with the IndexWriter's reader.
					indexAccess.searcher.close();
					indexAccess.reader.close();
					IndexReader reader = indexAccess.writer.getReader();
					indexAccess.reader = reader;
					indexAccess.searcher = new IndexSearcher(reader);
				}
			} catch (IOException e) {
				throw convertException(e);
			}
			// */
		}

		/**
		 * Delete a row from the index.
		 * 
		 * @param row the row
		 */
		protected void delete(Object[] row) throws SQLException {
			String query = getQuery(row);
			try {
				Term term = new Term(LUCENE_FIELD_QUERY, query);
				/*
				 * ## LUCENE2 ## indexAccess.modifier.deleteDocuments(term); //
				 */
				// ## LUCENE3 ##
				indexAccess.writer.deleteDocuments(term);
				// */
			} catch (IOException e) {
				throw convertException(e);
			}
		}

		private String getQuery(Object[] row) throws SQLException {
			StatementBuilder buff = new StatementBuilder();
			if (schema != null) {
				buff.append(StringUtils.quoteIdentifier(schema)).append('.');
			}
			buff.append(StringUtils.quoteIdentifier(table)).append(" WHERE ");
			for (int columnIndex : keys) {
				buff.appendExceptFirst(" AND ");
				buff.append(StringUtils.quoteIdentifier(columns[columnIndex]));
				Object o = row[columnIndex];
				if (o == null) {
					buff.append(" IS NULL");
				} else {
					buff.append('=').append(FullText.quoteSQL(o, columnTypes[columnIndex]));
				}
			}
			return buff.toString();
		}
	}

	/**
	 * A wrapper for the Lucene writer and searcher.
	 */
	static class IndexAccess {

		/**
		 * The index modified.
		 */
		/*
		 * ## LUCENE2 ## IndexModifier modifier; //
		 */

		/**
		 * The index writer.
		 */
		// ## LUCENE3 ##
		IndexWriter writer;
		// */

		/**
		 * The index reader.
		 */
		// ## LUCENE3 ##
		IndexReader reader;
		// */

		/**
		 * The index searcher.
		 */
		// ## LUCENE3 ##
		Searcher searcher;
		// */
	}

}

package org.dashjoin.service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.dashjoin.model.QueryMeta;
import org.dashjoin.model.Table;
import org.dashjoin.service.QueryEditor.AddColumnRequest;
import org.dashjoin.service.QueryEditor.Col;
import org.dashjoin.service.QueryEditor.ColCondition;
import org.dashjoin.service.QueryEditor.DistinctRequest;
import org.dashjoin.service.QueryEditor.InitialQueryRequest;
import org.dashjoin.service.QueryEditor.MoveColumnRequest;
import org.dashjoin.service.QueryEditor.QueryColumn;
import org.dashjoin.service.QueryEditor.QueryDatabase;
import org.dashjoin.service.QueryEditor.QueryResponse;
import org.dashjoin.service.QueryEditor.RemoveColumnRequest;
import org.dashjoin.service.QueryEditor.RenameRequest;
import org.dashjoin.service.QueryEditor.SetWhereRequest;
import org.dashjoin.service.QueryEditor.SortRequest;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.MySQLGroupConcat;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.Distinct;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;

/**
 * SQL implementation of the query editor backend
 */
public class SQLEditor implements QueryEditorInternal {

  SQLDatabase db;
  Services services;

  public SQLEditor(Services services, SQLDatabase db) {
    this.db = db;
    this.services = services;
  }

  @Override
  public QueryResponse rename(RenameRequest ac) throws Exception {

    if (ac == null || ac.col == null || ac.name == null)
      throw new IllegalArgumentException();
    if ((ac.col.column == null) || (ac.col.table == null) || (ac.col.table.isEmpty()))
      throw new IllegalArgumentException("request col must be TABLE.COLUMN: " + ac.col);

    // make sure the name actually changes (avoid select A as A from ...)
    if (ac.name.equals(ac.col.column)) {
      QueryResponse res = new QueryResponse();
      res.query = ac.query;
      return res;
    }

    Select stmt = (Select) CCJSqlParserUtil.parse(ac.query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();

    for (SelectItem s : body.getSelectItems()) {
      if (equals(s, ac.col)) {
        Alias alias = null;
        // If the name to set is "", remove the alias
        if (!ac.name.isEmpty())
          alias = new Alias("\"" + ac.name + "\"");
        ((SelectExpressionItem) s).setAlias(alias);
      }
    }

    return prettyPrint(ac.database, body, ac.limit);
  }

  @Override
  public QueryResponse distinct(DistinctRequest ac) throws Exception {

    if (ac == null || ac.distinct == null)
      throw new IllegalArgumentException();

    Select stmt = (Select) CCJSqlParserUtil.parse(ac.query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();

    if (ac.distinct) {
      // #152 - distinct requires order by clause to be included in the result
      body.setOrderByElements(null);
      body.setDistinct(new Distinct());
    } else
      body.setDistinct(null);

    if (ac.querylimit != null) {
      Limit limit = new Limit();
      limit.setRowCount(new LongValue(ac.querylimit));
      body.setLimit(limit);
    } else {
      body.setLimit(null);
    }

    return prettyPrint(ac.database, body, ac.limit);
  }

  @Override
  public QueryResponse sort(SortRequest ac) throws Exception {

    if (ac == null || ac.col == null)
      throw new IllegalArgumentException();
    if ((ac.col.column == null) || (ac.col.table == null) || (ac.col.table.isEmpty()))
      throw new IllegalArgumentException("request col must be TABLE.COLUMN: " + ac.col);

    Select stmt = (Select) CCJSqlParserUtil.parse(ac.query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();

    OrderByElement order = new OrderByElement();

    for (SelectItem s : body.getSelectItems()) {
      if (s instanceof SelectExpressionItem)
        if (equals(s, ac.col))
          order.setExpression(((SelectExpressionItem) s).getExpression());
    }

    if ("asc".equalsIgnoreCase(ac.order))
      order.setAsc(true);
    else if ("desc".equalsIgnoreCase(ac.order))
      order.setAsc(false);
    else
      order = null;
    body.setOrderByElements(order == null ? null : Arrays.asList(order));

    return prettyPrint(ac.database, body, ac.limit);
  }

  @Override
  public QueryResponse addColumn(AddColumnRequest ac) throws Exception {

    if (ac == null || ac.add == null)
      throw new IllegalArgumentException();

    Select stmt = (Select) CCJSqlParserUtil.parse(ac.query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();

    if (body.getGroupBy() != null)
      throw new Exception("Cannot join an aggregated query");

    if (containsTable(body, ac.add.table)) {
      // no join required, same table
      body.getSelectItems().add(getSelectExpr(ac.add));
    } else {

      if (ac.col == null)
        throw new IllegalArgumentException();
      if ((ac.col.column == null) || (ac.col.table == null) || (ac.col.table.isEmpty()))
        throw new IllegalArgumentException("request col must be TABLE.COLUMN: " + ac.col);

      Join join = new Join();
      join.setInner(true);
      EqualsTo eq = new EqualsTo();
      eq.setLeftExpression(getColumn(ac.col));

      Col addPk = db.getPk(ac.add.table);
      Col selectedPk = db.getPk(ac.col.table);
      if ((!addPk.column.equals(ac.add.column)) && (!selectedPk.column.equals(ac.col.column)))
        // both columns are non PKs, use the PK of the add table
        eq.setRightExpression(getColumn(db.getPk(ac.add.table)));
      else if ((addPk.column.equals(ac.add.column)) && (selectedPk.column.equals(ac.col.column))) {
        // both columns are PKs, use the PK of the add table
        Col fk = db.getFk(ac.col.table, ac.add.table);
        if (fk == null) {
          // Check inverse relation (pointing back)
          fk = db.getFk(ac.add.table, ac.col.table);
          eq.setLeftExpression(getColumn(ac.add));
        }
        eq.setRightExpression(getColumn(fk));
      } else if ((!addPk.column.equals(ac.add.column))
          && (selectedPk.column.equals(ac.col.column))) {
        Col fk = db.getFk(ac.col.table, ac.add.table);
        if (fk == null) {
          // Check inverse relation (pointing back)
          fk = db.getFk(ac.add.table, ac.col.table);
          eq.setLeftExpression(getColumn(addPk));
        }
        eq.setRightExpression(getColumn(fk));
      } else
        eq.setRightExpression(getColumn(ac.add));
      join.setOnExpression(eq);
      join.setRightItem(getTableWithSchema(ac.add.table));
      if (body.getJoins() == null)
        body.setJoins(new ArrayList<>());
      body.getJoins().add(join);
      body.getSelectItems().add(getSelectExpr(ac.add));
    }
    return prettyPrint(ac.database, body, ac.limit);
  }

  @Override
  public QueryResponse removeColumn(RemoveColumnRequest ac) throws Exception {

    if (ac == null)
      throw new IllegalArgumentException();
    if ((ac.col == null) || (ac.col.column == null) || (ac.col.table == null)
        || (ac.col.table.isEmpty()))
      throw new IllegalArgumentException("request col must be TABLE.COLUMN: " + ac.col);

    Select stmt = (Select) CCJSqlParserUtil.parse(ac.query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();

    List<SelectItem> sis = body.getSelectItems();
    if (sis.size() == 1)
      throw new IllegalArgumentException("Cannot remove only select projection");

    for (int i = 0; i < sis.size(); i++) {
      SelectItem si = sis.get(i);
      if (equals(si, ac.col)) {
        sis.remove(i);
        break;
      }
    }

    return prettyPrint(ac.database, body, ac.limit);
  }

  @Override
  public QueryResponse setWhere(SetWhereRequest ac) throws Exception {
    if (ac == null || ac.cols == null)
      throw new IllegalArgumentException();
    for (ColCondition cc : ac.cols)
      if ((cc.col == null) || (cc.col.column == null) || (cc.col.table == null)
          || (cc.col.table.isEmpty()))
        throw new IllegalArgumentException("request col must be TABLE.COLUMN: " + cc.col);

    Select stmt = (Select) CCJSqlParserUtil.parse(ac.query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();

    Expression where = null;
    for (ColCondition c : ac.cols) {
      if (c.condition == null)
        continue;
      Expression and = CCJSqlParserUtil.parseCondExpression(getColumn(c.col) + " " + c.condition);

      if (where == null)
        where = and;
      else
        where = new AndExpression(where, and);
    }
    body.setWhere(where);

    return prettyPrint(ac.database, body, ac.limit);
  }


  @Override
  public QueryResponse setGroupBy(SetWhereRequest ac) throws Exception {
    if (ac == null || ac.cols == null)
      throw new IllegalArgumentException();
    for (ColCondition cc : ac.cols)
      if ((cc.col == null) || (cc.col.column == null) || (cc.col.table == null)
          || (cc.col.table.isEmpty()))
        throw new IllegalArgumentException("request col must be TABLE.COLUMN: " + cc.col);

    Select stmt = (Select) CCJSqlParserUtil.parse(ac.query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();

    List<Expression> groupByExpressions = new ArrayList<>();
    for (ColCondition c : ac.cols) {

      if (c.condition == null)
        throw new IllegalArgumentException();

      if (c.condition.equals("GROUP BY"))
        groupByExpressions.add(getColumn(c.col));
    }
    GroupByElement groupBy = new GroupByElement();
    groupBy.setGroupByExpressions(groupByExpressions);

    // if all variables are in the group by, it is no longer a group by query
    if (groupByExpressions.size() == body.getSelectItems().size()) {
      groupByExpressions.clear();
    }

    body.setGroupByElement(groupByExpressions.isEmpty() ? null : groupBy);

    // might have to change order by to count(...) - simply remove it
    body.setOrderByElements(null);

    int idx = 0;
    for (SelectItem s : body.getSelectItems()) {
      SelectExpressionItem se = (SelectExpressionItem) s;
      ColCondition x = ac.cols.get(idx++);

      if (x.condition == null)
        throw new IllegalArgumentException();

      boolean isGroupByVar = x.condition.equals("GROUP BY");
      if (se.getExpression() instanceof Column) {
        // currently a column
        if (!isGroupByVar) {
          Function func = new Function();

          if (x.condition.endsWith(" DISTINCT")) {
            func.setDistinct(true);
            x.condition = x.condition.substring(0, x.condition.length() - " DISTINCT".length());
          } else {
            func.setDistinct(false);
          }

          func.setName(x.condition);
          func.setParameters(new ExpressionList(se.getExpression()));
          se.setExpression(func);
        }
      } else {
        // currently a COUNT(col)
        ExpressionList params = getExpressionList(se.getExpression());
        if (isGroupByVar) {
          Expression col = params.getExpressions().get(0);
          se.setExpression(col);
        } else {
          // Note: because GROUP_CONCAT is modelled as MySQLGroupConcat, which is
          // NOT a function, we create a new function with operation "GROUP_CONCAT" to keep the code
          // compatible.
          // Strictly speaking: this is dirty...
          Function func = new Function();
          if (x.condition.endsWith(" DISTINCT")) {
            func.setDistinct(true);
            x.condition = x.condition.substring(0, x.condition.length() - " DISTINCT".length());
          } else {
            func.setDistinct(false);
          }

          func.setName(x.condition);
          func.setParameters(params);
          se.setExpression(func);
        }
      }
    }

    return prettyPrint(ac.database, body, ac.limit);
  }

  @Override
  public QueryResponse moveColumn(MoveColumnRequest ac) throws Exception {
    if (ac == null || ac.position < 0)
      throw new IllegalArgumentException();
    if ((ac.col == null) || (ac.col.column == null) || (ac.col.table == null)
        || (ac.col.table.isEmpty()))
      throw new IllegalArgumentException("request col must be TABLE.COLUMN: " + ac.col);

    Select stmt = (Select) CCJSqlParserUtil.parse(ac.query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();

    List<SelectItem> sis = body.getSelectItems();
    SelectItem moved = null;
    for (int i = 0; i < sis.size(); i++) {
      SelectItem si = sis.get(i);
      if (equals(si, ac.col)) {
        moved = sis.remove(i);
        break;
      }
    }

    if (moved == null)
      throw new Exception("Column not found: " + ac.col);

    sis.add(ac.position, moved);

    return prettyPrint(ac.database, body, ac.limit);
  }

  @Override
  public QueryResponse noop(QueryDatabase query) throws Exception {
    Statement s = CCJSqlParserUtil.parse(query.query);
    if (!(s instanceof Select)) {
      throw new Exception("The query editor only supports select queries");
    }
    Select stmt = (Select) s;
    PlainSelect body = (PlainSelect) stmt.getSelectBody();
    return prettyPrint(query.database, body, query.limit);
  }

  /**
   * checks whether the select contains the table t already
   */
  static boolean containsTable(PlainSelect s, String t) {
    if (equals(s.getFromItem(), t))
      return true;

    if (s.getJoins() != null)
      for (Join j : s.getJoins())
        if (equals(j.getRightItem(), t))
          return true;

    return false;
  }

  /**
   * create a select expression item
   */
  SelectExpressionItem getSelectExpr(Col c) {
    SelectExpressionItem expr = new SelectExpressionItem();
    expr.setExpression(getColumn(c));
    return expr;
  }

  /**
   * convert a REST column representation to a SQL parser column representation
   */
  Column getColumn(Col col) {
    Column column = new Column();
    column.setColumnName(q(col.column));
    column.setTable(getTable(col.table));
    return column;
  }

  /**
   * construct a table object
   */
  net.sf.jsqlparser.schema.Table getTable(String t) {
    net.sf.jsqlparser.schema.Table table = new net.sf.jsqlparser.schema.Table();
    table.setName(q(t));
    return table;
  }

  /**
   * construct a table object
   */
  net.sf.jsqlparser.schema.Table getTableWithSchema(String t) {
    net.sf.jsqlparser.schema.Table table = new net.sf.jsqlparser.schema.Table();
    if (!schema().isEmpty())
      table.setSchemaName(schema().substring(0, schema().length() - 1));
    table.setName(q(t));
    return table;
  }

  /**
   * checks FromItem (Table) name against string t
   */
  static boolean equals(FromItem fi, String t) {
    if (fi instanceof net.sf.jsqlparser.schema.Table)
      return equals(((net.sf.jsqlparser.schema.Table) fi).getName(), t);
    return false;
  }

  /**
   * Gets the expression list (a.k.a. parameters) of the given SQL function
   * 
   * This is required because GROUP_CONCAT is not modelled as a function but as a distinct class.
   * 
   * @param o
   * @return
   */
  static ExpressionList getExpressionList(Object o) {
    if (o instanceof Function) {
      Function f = (Function) o;
      return f.getParameters();
    }
    if (o instanceof MySQLGroupConcat) {
      MySQLGroupConcat f = (MySQLGroupConcat) o;
      return f.getExpressionList();
    }
    throw new RuntimeException("Warning: unsupported query function: " + o);
  }

  /**
   * check SelectItem against col
   */
  static boolean equals(SelectItem si, Col col) {
    if (si instanceof SelectExpressionItem) {
      Expression e = ((SelectExpressionItem) si).getExpression();
      if (e instanceof Column)
        return equals((Column) e, col);

      ExpressionList exl = getExpressionList(e);

      if (exl.getExpressions().size() == 1) {
        Expression par0 = exl.getExpressions().get(0);
        if (par0 instanceof Column)
          return equals((Column) par0, col);
      }
    }
    return false;
  }

  static boolean equals(Column e, Col col) {
    return equals(e.getTable(), col.table) && equals(e.getColumnName(), col.column);
  }

  /**
   * checks string equality while disregarding quotes
   */
  static boolean equals(String a, String b) {
    if (!a.startsWith("\""))
      a = "\"" + a + "\"";
    if (!b.startsWith("\""))
      b = "\"" + b + "\"";
    return a.equals(b);
  }

  List<SelectItem> selectStar(FromItem fi) {
    List<SelectItem> res = new ArrayList<>();

    if (!(fi instanceof net.sf.jsqlparser.schema.Table))
      return res;

    String name = ((net.sf.jsqlparser.schema.Table) fi).getName();
    if (name.startsWith("\"") && name.endsWith("\""))
      name = name.substring(1, name.length() - 1);

    Table t = db.tables.get(name);
    for (String col : t.properties.keySet()) {
      SelectExpressionItem se = new SelectExpressionItem();
      Col c = Col.col(t.name, col);
      se.setExpression(getColumn(c));
      res.add(se);
    }
    return res;
  }

  /**
   * simplified and slightly adjusted (indent + line breaks) version of PlainSelect.toString()
   */
  QueryResponse prettyPrint(String database, PlainSelect body, Integer limit) throws Exception {

    // replace select *
    if (body.getSelectItems().size() == 1)
      if (body.getSelectItems().get(0) instanceof AllColumns) {

        SelectItem allColumns = body.getSelectItems().remove(0);

        // Replace SELECT * with SELECT col1, col2, ...
        boolean haveSelects = false;
        if (body.getFromItem() instanceof net.sf.jsqlparser.schema.Table)
          haveSelects |= body.getSelectItems().addAll(selectStar(body.getFromItem()));

        if (body.getJoins() != null)
          for (Join j : body.getJoins())
            if (j.getRightItem() instanceof net.sf.jsqlparser.schema.Table)
              haveSelects |= body.getSelectItems().addAll(selectStar(j.getRightItem()));

        // If we found no columns to select, revert to SELECT *
        // Note: this only happens if we got no metadata for the current table
        if (!haveSelects)
          body.addSelectItems(allColumns);
      }

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT" + (body.getDistinct() == null ? "" : " DISTINCT") + "\n  ");
    sql.append(PlainSelect.getStringList(body.getSelectItems()));

    FromItem fromItem = body.getFromItem();
    List<Join> joins = body.getJoins();
    sql.append("\nFROM\n  ").append(fromItem);
    if (joins != null) {
      Iterator<Join> it = joins.iterator();
      while (it.hasNext()) {
        Join join = it.next();
        if (join.isSimple()) {
          sql.append(", ").append(join);
        } else {
          sql.append("\n    ").append(join);
        }
      }
    }

    Expression where = body.getWhere();
    if (where != null) {
      sql.append("\nWHERE\n  ").append(where);
    }
    GroupByElement groupBy = body.getGroupBy();
    if (groupBy != null) {
      sql.append("\nGROUP BY\n  ").append(groupBy.toString().substring("GROUP BY".length()));
    }
    Expression having = body.getHaving();
    if (having != null) {
      sql.append("\nHAVING\n  ").append(having);
    }
    if (body.getOrderByElements() != null)
      sql.append("\nORDER BY\n  ")
          .append(PlainSelect.getFormatedList(body.getOrderByElements(), ""));
    if (body.getLimit() != null) {
      sql.append("\n" + body.getLimit().toString().substring(1));
      limit = Integer.parseInt(body.getLimit().getRowCount() + "");
    }

    // make sure we can parse the query again
    Statement pretty = CCJSqlParserUtil.parse(sql.toString());

    // prepare return value
    QueryResponse res = new QueryResponse();
    res.database = database;
    res.limit = limit;
    res.query = sql.toString();

    // call query method to get the QueryColumn metadata
    res.metadata = db.getMetadata(res.query);

    // call "new" query method to get the data (create a tmp catalog entry)
    QueryMeta value = new QueryMeta();
    value.query = res.query;
    value.type = "read";

    res.data = db.query(value, null, limit);

    // set the field names
    res.fieldNames = new ArrayList<>();
    for (QueryColumn qc : res.metadata) {
      if (qc.col.column.equals(qc.displayName))
        res.fieldNames.add(qc.col.toString());
      else
        res.fieldNames.add(qc.displayName);
    }

    // finally, compute join options
    res.joinOptions = new ArrayList<>();

    // current tables and tables one "join" away
    Map<Table, Col> tables = new HashMap<>();
    for (QueryColumn qc : res.metadata) {
      Table t = db.getSchema(qc.col.table);
      if (t == null)
        continue;
      tables.put(t, db.getPk(qc.col.table));
      for (Table r : db.getRelatedTables(t, true))
        tables.put(r, db.getFk(r.name, qc.col.table));
      for (Table r : db.getRelatedTables(t, false))
        tables.put(r, db.getPk(qc.col.table));
    }

    // get samples and table metadata
    samplesAndMetadata(res, tables);

    res.distinct = body.getDistinct() != null;
    res.querylimit =
        body.getLimit() == null ? null : Integer.parseInt(body.getLimit().getRowCount() + "");
    res.compatibilityError = compatibilityError(pretty);

    return res;
  }

  void samplesAndMetadata(QueryResponse res, Map<Table, Col> tables) throws SQLException {
    try (Connection con = db.getConnection()) {
      for (Entry<Table, Col> t : tables.entrySet())
        try (java.sql.Statement stmt = con.createStatement()) {
          stmt.setMaxRows(1);
          try (ResultSet rs = stmt.executeQuery("select * from " + schema() + q(t.getKey().name))) {
            while (rs.next()) {
              ResultSetMetaData rsmd = rs.getMetaData();
              for (int c = 1; c <= rsmd.getColumnCount(); c++) {
                String colname = rsmd.getColumnName(c);
                if (!contains(res.metadata, t.getKey().name, colname)) {
                  AddColumnRequest e = new AddColumnRequest();
                  e.preview = SQLDatabase.serialize(rsmd, rs, c);
                  e.add = Col.col(t.getKey().name, colname);
                  e.col = t.getValue();
                  res.joinOptions.add(e);
                }
              }
              break;
            }
          }
        }
    }
  }

  static String compatibilityError(Statement stmt) {

    if (!(stmt instanceof Select))
      return "Only plain SELECT queries are supported (no WITH or VALUES clauses)";

    SelectBody body = ((Select) stmt).getSelectBody();
    if (!(body instanceof PlainSelect))
      return "Only plain SELECT queries are supported (no WITH or VALUES clauses)";

    PlainSelect select = (PlainSelect) body;
    if (!(select.getFromItem() instanceof net.sf.jsqlparser.schema.Table))
      return "Only select from table is supported";

    if (select.getHaving() != null)
      return "Having not supported";

    if (select.getJoins() != null)
      for (Join j : select.getJoins())
        if (!(j.getRightItem() instanceof net.sf.jsqlparser.schema.Table))
          return "Only select from table is supported";

    for (SelectItem si : select.getSelectItems()) {
      if (si instanceof SelectExpressionItem) {
        Expression e = ((SelectExpressionItem) si).getExpression();
        if (e instanceof Function) {
          if (((Function) e).getParameters() == null)
            return "Unsupported expression: " + si;
          List<Expression> x = ((Function) e).getParameters().getExpressions();
          if (x.size() != 1)
            return "Unsupported expression: " + si;
          e = x.get(0);
        }
        if (e instanceof MySQLGroupConcat)
          continue;
        if (e instanceof Column)
          continue;
        return "Unsupported expression: " + si;
      } else {
        return "Select * and TABLE.* are not supported";
      }
    }

    try {
      SQLEditor.parseWhere(false, new HashMap<>(), select.getWhere());
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }

    return null;
  }

  static boolean contains(List<QueryColumn> cols, String table, String column) {
    for (QueryColumn qc : cols)
      if (column.equals(qc.col.column))
        if (table.equals(qc.col.table))
          return true;
    return false;
  }

  static void parseWhere(boolean ignoreUnknown, Map<Col, String> res, Expression expr) {
    if (expr instanceof AndExpression) {
      BinaryExpression b = (BinaryExpression) expr;
      parseWhere(ignoreUnknown, res, b.getLeftExpression());
      parseWhere(ignoreUnknown, res, b.getRightExpression());
      return;
    }
    if (expr instanceof Parenthesis) {
      parseWhere(ignoreUnknown, res, ((Parenthesis) expr).getExpression());
      return;
    }
    if (expr instanceof OrExpression) {
      BinaryExpression b = (BinaryExpression) expr;
      parseWhere(ignoreUnknown, res, b.getLeftExpression());
      parseWhere(ignoreUnknown, res, b.getRightExpression());
      return;
    }
    if (expr instanceof ComparisonOperator) {
      ComparisonOperator o = (ComparisonOperator) expr;

      if (!(o.getLeftExpression() instanceof Column))
        if (!ignoreUnknown)
          throw new IllegalArgumentException("Left side of where expressions must be a column");
        else
          return;

      Column left = (Column) o.getLeftExpression();
      res.put(
          colNoQuotes(SQLDatabase.s(left.getTable().getName()),
              SQLDatabase.s(left.getColumnName())),
          o.getStringExpression() + " " + o.getRightExpression().toString());
      return;
    }
    if (expr instanceof LikeExpression) {
      LikeExpression o = (LikeExpression) expr;
      Column left = (Column) o.getLeftExpression();
      if (left.getTable() == null)
        throw new IllegalArgumentException(left + " has no table information");
      res.put(colNoQuotes(left.getTable().getName(), left.getColumnName()),
          o.getStringExpression() + " " + o.getRightExpression().toString());
      return;
    }
    if (expr instanceof Between) {
      Between o = (Between) expr;

      if (!(o.getLeftExpression() instanceof Column))
        if (!ignoreUnknown)
          throw new IllegalArgumentException("Left side of where expressions must be a column");
        else
          return;

      Column left = (Column) o.getLeftExpression();
      res.put(colNoQuotes(left.getTable().getName(), left.getColumnName()),
          "BETWEEN " + o.getBetweenExpressionStart() + " AND " + o.getBetweenExpressionEnd());
      return;
    }
    if (expr instanceof IsNullExpression) {
      IsNullExpression o = (IsNullExpression) expr;
      Column left = (Column) o.getLeftExpression();
      res.put(colNoQuotes(left.getTable().getName(), left.getColumnName()),
          "IS " + (o.isNot() ? "NOT " : "") + "NULL");
      return;
    }
    if (expr == null)
      return;

    if (!ignoreUnknown)
      throw new IllegalArgumentException("Unsupported expression: " + expr + " " + expr.getClass());
  }

  static Col colNoQuotes(String table, String column) {
    return Col.col(table.replaceAll("\"", ""), column.replaceAll("\"", ""));
  }

  @Override
  public QueryResponse getInitialQuery(InitialQueryRequest ac) throws Exception {
    Table s = services.getConfig().getSchema(ac.table);
    String clazz = s.name;
    Table t = db.tables.get(clazz);
    Iterator<String> i = t.properties.keySet().iterator();
    String query = i.hasNext() ? "select " + q(clazz) + "." + q(i.next()) + "" : "select *";
    if (i.hasNext())
      query = query + ", " + q(clazz) + "." + q(i.next());
    query = query + " from " + schema() + q(clazz) + "";

    Select stmt = (Select) CCJSqlParserUtil.parse(query);
    PlainSelect body = (PlainSelect) stmt.getSelectBody();
    return prettyPrint(db.ID, body, ac.limit);
  }

  String q(String column) {
    return db.q(column);
  }

  String schema() {
    return db.schema();
  }
}

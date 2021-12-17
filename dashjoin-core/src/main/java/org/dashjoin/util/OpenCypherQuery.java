package org.dashjoin.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.SecurityContext;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.dashjoin.model.AbstractDatabase;
import org.dashjoin.model.Property;
import org.dashjoin.service.Data;
import org.dashjoin.service.Data.Resource;
import org.dashjoin.service.Services;
import org.dashjoin.util.cypher.CypherBaseListener;
import org.dashjoin.util.cypher.CypherLexer;
import org.dashjoin.util.cypher.CypherListener;
import org.dashjoin.util.cypher.CypherParser;
import org.dashjoin.util.cypher.CypherParser.OC_PatternElementChainContext;
import org.dashjoin.util.cypher.CypherParser.OC_PatternElementContext;
import org.dashjoin.util.cypher.CypherParser.OC_PatternPartContext;
import org.dashjoin.util.cypher.CypherParser.OC_ReturnContext;
import org.dashjoin.util.cypher.CypherParser.OC_VariableContext;

/**
 * Represents an OpenCypher query, where a small subset of the language is supported.
 */
public class OpenCypherQuery {

  /**
   * query template fragment like [var:collection] or (var:edge)
   */
  public static class Table {

    /**
     * variable bound to this step
     */
    public String variable;

    /**
     * table / collection / edge name
     */
    public String name;

    /**
     * name escaped with back ticks
     */
    boolean nameEscaped;

    /**
     * represents equality contraints on the table
     */
    public String key;

    /**
     * represents equality contraints on the table
     */
    public String value;

    /**
     * edge or collection
     */
    public boolean isEdge;

    /**
     * multi hop
     */
    public boolean star;

    /**
     * multi hop lower bound
     */
    public Integer from;

    /**
     * multi hop upper bound
     */
    public Integer to;

    /**
     * parses an ANTLR parse tree fragment tree.getText() which looks like [var:Collection
     * {key:value}] or (var:edge *1..2)
     */
    public Table(String s, boolean isEdge) {
      this.isEdge = isEdge;
      s = s.substring(1, s.length() - 1).trim();
      if (s.endsWith("}")) {
        int start = s.lastIndexOf('{');
        int colon = s.indexOf(':', start + 1);
        key = s.substring(start + 1, colon).trim();
        value = s.substring(colon + 1, s.length() - 1).trim();
        s = s.substring(0, start);
      }
      if (s.endsWith("*")) {
        star = true;
        s = "";
      } else if (s.contains("*")) {
        star = true;
        int st = s.lastIndexOf('*');
        if (s.substring(st).matches("[*][0-9]*[.][.][0-9]*")) {
          String pattern = s.substring(st + 1).trim();
          if (pattern.startsWith(".."))
            to = Integer.parseInt(pattern.substring(2).trim());
          else if (pattern.endsWith(".."))
            from = Integer.parseInt(pattern.substring(0, pattern.length() - 2).trim());
          else {
            from = Integer.parseInt(pattern.split("[.][.]")[0].trim());
            to = Integer.parseInt(pattern.split("[.][.]")[1].trim());
          }
          s = s.substring(0, st);
        }
      }
      if (!s.contains(":"))
        variable = s;
      else {
        variable = s.split(":")[0].trim();
        name = s.substring(variable.length() + 1);
        if (name.startsWith("`") && name.endsWith("`")) {
          name = name.substring(1, name.length() - 1);
          nameEscaped = true;
        }
      }
    }

    @Override
    public String toString() {
      String kv = key != null ? "{" + key + ": " + value + "}" : "";
      String ft = "";
      if (star)
        if (from == null && to == null)
          ft = "*";
        else if (to == null)
          ft = "*" + from + "..";
        else if (from == null)
          ft = "*.." + to;
        else
          ft = "*" + from + ".." + to;
      String _name = nameEscaped ? "`" + name + "`" : name;
      if (isEdge)
        return "[" + variable + (name == null ? "" : (":" + _name)) + kv + ft + "]";
      else
        return "(" + variable + (name == null ? "" : (":" + _name)) + kv + ft + ")";
    }
  }

  /**
   * chain element thata follows the initial context "table"
   */
  public static class Chain {

    /**
     * edge
     */
    public Table edge;

    /**
     * collection
     */
    public Table table;

    /**
     * incoming or outgoing
     */
    public boolean left2right;

    @Override
    public String toString() {
      if (left2right)
        return "-" + edge + "->" + table;
      else
        return "<-" + edge + "-" + table;
    }
  }

  /**
   * represents a path which is the result of a graph traversal
   */
  public static class Path {

    /**
     * starting record
     */
    public Map<String, Object> start;

    /**
     * list of steps leading to the destination
     */
    public List<Step> steps = new ArrayList<>();
  }

  /**
   * step in a path
   */
  public static class Step {

    /**
     * after taking the step, we arrive at this record
     */
    public Map<String, Object> end;

    /**
     * we take the step across this edge
     */
    public Map<String, Object> edge;
  }

  /**
   * name of the path variable: MATCH path=(...
   */
  public String pathVariable;

  /**
   * initial context to match
   */
  public Table context;

  /**
   * template links
   */
  public List<Chain> links = new ArrayList<>();

  /**
   * return / projection
   */
  public List<List<String>> ret = new ArrayList<>();

  /**
   * parse query string into instance
   */
  public OpenCypherQuery(String query) throws IOException {

    BaseErrorListener errorHandler = new BaseErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
          int charPositionInLine, String msg, RecognitionException e) {
        throw new RuntimeException("line " + line + ":" + charPositionInLine + " " + msg);
      }
    };

    CharStream input = CharStreams.fromStream(new ByteArrayInputStream(query.getBytes()));
    CypherLexer lexer = new CypherLexer(input);
    lexer.addErrorListener(errorHandler);
    CommonTokenStream token = new CommonTokenStream(lexer);
    CypherParser parser = new CypherParser(token);
    parser.addErrorListener(errorHandler);
    ParseTree tree = parser.oC_Cypher();

    CypherListener listener = new CypherBaseListener() {

      @Override
      public void exitOC_PatternPart(OC_PatternPartContext ctx) {
        if (ctx.getChild(0) instanceof OC_VariableContext)
          pathVariable = ctx.getChild(0).getText();
      }

      @Override
      public void exitOC_PatternElement(OC_PatternElementContext ctx) {
        String s = ctx.getChild(0).getText().trim();
        context = new Table(s, false);
      }

      @Override
      public void exitOC_PatternElementChain(OC_PatternElementChainContext ctx) {
        Chain c = new Chain();
        String rel = ctx.getChild(0).getText();
        if (rel.endsWith("->")) {
          c.left2right = true;
          rel = rel.substring(1, rel.length() - 2);
        } else
          rel = rel.substring(2, rel.length() - 1);
        c.edge = new Table(rel, true);
        c.table = new Table(ctx.getChild(1).getText(), false);
        links.add(c);
      }

      @Override
      public void exitOC_Return(OC_ReturnContext ctx) {
        for (String part : ctx.getChild(1).getText().trim().split(","))
          ret.add(Arrays.asList(part.trim().split("\\.")));
      }
    };

    ParseTreeWalker walker = new ParseTreeWalker();
    walker.walk(listener, tree);
  }

  /**
   * helper used to debug / visualize the parse tree
   */
  void print(ParseTree tree, String indent) {
    System.out.println(indent + tree.getClass().toString()
        .substring("class org.dashjoin.util.cypher.CypherParser$".length()) + " " + tree.getText());
    for (int i = 0; i < tree.getChildCount(); i++)
      print(tree.getChild(i), indent + "  ");
  }

  @Override
  public String toString() {
    String s = "MATCH " + (pathVariable == null ? "" : pathVariable + "=") + context;

    for (Chain c : links)
      s = s + c;

    List<String> projections = new ArrayList<>();
    for (List<String> p : ret)
      projections.add(String.join(".", p));
    return s + " RETURN " + String.join(", ", projections);
  }

  @SuppressWarnings("unchecked")
  public List<Map<String, Object>> run(Services service, Data data, SecurityContext sc,
      Map<String, Object> arguments) throws Exception {
    Map<String, AbstractDatabase> cache = new HashMap<>();
    List<Map<String, Object>> res = new ArrayList<>();
    String[] table = Escape.parseTableID(context.name);
    for (Map<String, Object> row : data.all(sc, table[1], table[2], null, null, null, false,
        arguments)) {

      String[] parts = Escape.parseTableID(context.name);
      AbstractDatabase db = cache.get(parts[1]);
      if (db == null) {
        db = service.getConfig().getDatabase(parts[0] + '/' + parts[1]);
        cache.put(parts[1], db);
      }
      List<Object> keys = new ArrayList<>();
      for (Property prop : db.tables.get(parts[2]).properties.values()) {
        if (prop.pkpos != null) {
          while (keys.size() <= prop.pkpos)
            keys.add(null);
          keys.set(prop.pkpos, row.get(prop.name));
        }
      }
      row.put("_dj_resource", Resource.of(parts[1], parts[2], keys));
      Map<String, Object> vars = new LinkedHashMap<>();
      vars.put(context.variable, row);

      Map<String, Object> path = MapUtil.of("start", row, "steps", new ArrayList<>());
      if (pathVariable != null)
        vars.put(pathVariable, path);

      for (Chain link : links) {
        Map<String, Object> edge =
            MapUtil.of("_dj_edge", link.edge.name, "_dj_outbound", link.left2right);
        vars.put(link.edge.variable, edge);
        String pk = null;
        for (Property p : db.tables.get(table[2]).properties.values())
          if (p.pkpos != null)
            pk = p.name;
        if (row != null)
          row = (Map<String, Object>) data.traverse(sc, table[1], table[2], "" + row.get(pk),
              link.edge.name);
        vars.put(link.table.variable, row);
        ((List<Object>) path.get("steps")).add(MapUtil.of("edge", edge, "end", row));
      }

      Map<String, Object> projected = new LinkedHashMap<>();
      for (List<String> var : ret) {
        Object current = vars;
        for (String p : var) {
          if (current != null)
            current = ((Map<String, Object>) current).get(p);
        }
        projected.put(String.join(".", var), current);
      }

      res.add(projected);
    }
    return res;
  }
}

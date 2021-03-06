package sqlancer.sqlite3.queries;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.QueryAdapter;
import sqlancer.Randomly;
import sqlancer.sqlite3.SQLite3Errors;
import sqlancer.sqlite3.SQLite3Provider.SQLite3GlobalState;
import sqlancer.sqlite3.SQLite3Visitor;
import sqlancer.sqlite3.ast.SQLite3Aggregate;
import sqlancer.sqlite3.ast.SQLite3Aggregate.SQLite3AggregateFunction;
import sqlancer.sqlite3.ast.SQLite3Expression;
import sqlancer.sqlite3.ast.SQLite3Expression.Join;
import sqlancer.sqlite3.ast.SQLite3Expression.Join.JoinType;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3ColumnName;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3PostfixUnaryOperation;
import sqlancer.sqlite3.ast.SQLite3Expression.SQLite3PostfixUnaryOperation.PostfixUnaryOperator;
import sqlancer.sqlite3.ast.SQLite3Select;
import sqlancer.sqlite3.ast.SQLite3Select.SelectType;
import sqlancer.sqlite3.ast.SQLite3UnaryOperation;
import sqlancer.sqlite3.ast.SQLite3UnaryOperation.UnaryOperator;
import sqlancer.sqlite3.gen.SQLite3Common;
import sqlancer.sqlite3.gen.SQLite3ExpressionGenerator;
import sqlancer.sqlite3.schema.SQLite3Schema;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Column;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Table;
import sqlancer.sqlite3.schema.SQLite3Schema.SQLite3Tables;

public class SQLite3MetamorphicTrueValueSynthesizer {

    // SELECT COUNT(*) FROM t0 WHERE <cond>;
    // SELECT SUM(count) FROM (SELECT <cond> IS TRUE as count FROM t0);
    // SELECT (SELECT COUNT(*) FROM t0 WHERE c0 IS NOT 0) = (SELECT COUNT(*) FROM
    // (SELECT c0 is NOT 0 FROM t0));

    private static final int NOT_FOUND = -1;

    private SQLite3Schema s;
    private Connection con;
    private final List<String> queries = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final SQLite3GlobalState globalState;

    public SQLite3MetamorphicTrueValueSynthesizer(SQLite3Schema s, Connection con, SQLite3GlobalState globalState) {
        this.s = s;
        this.con = con;
        this.globalState = globalState;
        SQLite3Errors.addExpectedExpressionErrors(errors);
        SQLite3Errors.addMatchQueryErrors(errors);

        // aggregate
        errors.add("misuse of aggregate");
        errors.add("second argument to nth_value must be a positive integer");
        errors.add("no such table");
        // errors.add("no such index"); // INDEXED BY
        // errors.add("no query solution"); // INDEXED BY
    }

    public void generateAndCheck() throws SQLException {
        queries.clear();
        SQLite3Tables randomTable = s.getRandomTableNonEmptyTables();
        List<SQLite3Column> columns = randomTable.getColumns();
        SQLite3Expression randomWhereCondition = getRandomWhereCondition(columns);
        List<SQLite3Expression> groupBys = Collections.emptyList(); // getRandomExpressions(columns);
        List<SQLite3Table> tables = randomTable.getTables();
        List<Join> joinStatements = new ArrayList<>();
        if (Randomly.getBoolean()) {
            int nrJoinClauses = (int) Randomly.getNotCachedInteger(0, tables.size());
            for (int i = 1; i < nrJoinClauses; i++) {
                SQLite3Expression joinClause = getRandomWhereCondition(columns);
                SQLite3Table table = Randomly.fromList(tables);
                tables.remove(table);
                JoinType options;
                options = Randomly.fromOptions(JoinType.INNER, JoinType.CROSS, JoinType.OUTER);
                if (options == JoinType.OUTER && tables.size() > 2) {
                    errors.add("ON clause references tables to its right");
                }
                Join j = new SQLite3Expression.Join(table, joinClause, options);
                joinStatements.add(j);
            }

        }
        int totalCount = getTotalCount(tables, groupBys, joinStatements);
        int trueCount = getSubsetCount(tables, randomWhereCondition, groupBys, joinStatements, Mode.TRUE);
        int falseCount = getSubsetCount(tables, randomWhereCondition, groupBys, joinStatements, Mode.FALSE);
        int nullCount = getSubsetCount(tables, randomWhereCondition, groupBys, joinStatements, Mode.ISNULL);
        if (totalCount == -1 || trueCount == -1 || falseCount == -1 || nullCount == -1) {
            throw new IgnoreMeException();
        }
        int totalCount2 = trueCount + falseCount + nullCount;
        if (totalCount != totalCount2) {
            throw new AssertionError(
                    totalCount + " " + totalCount2 + "\n" + queries.stream().collect(Collectors.joining("\n")));
        }
    }

    private int getTotalCount(List<SQLite3Table> list, List<SQLite3Expression> groupBys, List<Join> joinStatements)
            throws SQLException {
        SQLite3Select select = new SQLite3Select();
        select.setGroupByClause(groupBys);
        SQLite3Aggregate count = new SQLite3Aggregate(Arrays.asList(SQLite3ColumnName.createDummy("*")),
                SQLite3AggregateFunction.COUNT);
        select.setFetchColumns(Arrays.asList(count));
        select.setFromTables(SQLite3Common.getTableRefs(list, s));
        select.setSelectType(SelectType.ALL);
        select.setJoinClauses(joinStatements);
        int totalCount = 0;

        String totalString = SQLite3Visitor.asString(select);
        queries.add(totalString);
        QueryAdapter q = new QueryAdapter(totalString, errors);
        try (ResultSet rs = q.executeAndGet(con)) {
            if (rs == null) {
                return NOT_FOUND;
            } else {
                if (rs.next()) {
                    totalCount = rs.getInt(1);
                }
                rs.getStatement().close();
            }
        }
        return totalCount;
    }

    private enum Mode {
        TRUE, FALSE, ISNULL;
    }

    private int getSubsetCount(List<SQLite3Table> list, SQLite3Expression condition, List<SQLite3Expression> groupBys,
            List<Join> joinStatements, Mode m) throws SQLException {
        SQLite3Select select = new SQLite3Select();
        select.setGroupByClause(groupBys);
        SQLite3Aggregate aggr = new SQLite3Aggregate(Arrays.asList(SQLite3ColumnName.createDummy("*")),
                SQLite3AggregateFunction.COUNT);
        select.setFetchColumns(Arrays.asList(aggr));
        select.setFromTables(SQLite3Common.getTableRefs(list, s));
        select.setSelectType(SelectType.ALL);
        select.setJoinClauses(joinStatements);
        if (m == Mode.TRUE) {
            select.setWhereClause(condition);
        } else if (m == Mode.FALSE) {
            select.setWhereClause(new SQLite3UnaryOperation(UnaryOperator.NOT, condition));
        } else if (m == Mode.ISNULL) {
            select.setWhereClause(new SQLite3PostfixUnaryOperation(PostfixUnaryOperator.ISNULL, condition));
        }
        int totalCount = 0;

        String totalString = SQLite3Visitor.asString(select);
        queries.add(totalString);
        QueryAdapter q = new QueryAdapter(totalString, errors);
        try (ResultSet rs = q.executeAndGet(con)) {
            if (rs == null) {
                return NOT_FOUND;
            } else {
                if (rs.next()) {
                    totalCount = rs.getInt(1);
                }
                rs.getStatement().close();
            }
        }
        return totalCount;
    }

    private SQLite3Expression getRandomWhereCondition(List<SQLite3Column> columns) {
        SQLite3ExpressionGenerator gen = new SQLite3ExpressionGenerator(globalState).setColumns(columns);
        // FIXME: enable match clause for multiple tables
        // if (randomTable.isVirtual()) {
        // gen.allowMatchClause();
        // }
        return gen.generateExpression();
    }

}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package x7.repository.dao;

import x7.core.bean.Criteria;
import x7.core.bean.Parsed;
import x7.core.bean.Parser;
import x7.core.config.Configs;
import x7.core.repository.X;
import x7.core.util.BeanUtilX;
import x7.core.util.StringUtil;
import x7.core.web.Direction;
import x7.core.web.Pagination;
import x7.repository.exception.PersistenceException;
import x7.repository.exception.RollbackException;
import x7.repository.exception.ShardingException;
import x7.repository.sharding.ShardingPolicy;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;


/**
 * 
 * Sharding MySQL
 * 
 * @author Sim
 *
 */
public class ShardingDaoImpl implements ShardingDao {

	private ExecutorService service = Executors.newCachedThreadPool();

	private static ShardingDaoImpl instance;

	public static ShardingDaoImpl getInstance() {
		if (instance == null) {
			instance = new ShardingDaoImpl();
		}
		return instance;
	}

	private ShardingDaoImpl() {
	}

	private Map<String, DataSource> dsWMap;
	private Map<String, DataSource> dsRMap;

	public void setDsWMap(Map<String, DataSource> dsWMap) {
		this.dsWMap = dsWMap;
	}

	public void setDsRMap(Map<String, DataSource> dsRMap) {
		this.dsRMap = dsRMap;
	}

	private String getKeyFieldName(Class clz) {
		Parsed parsed = Parser.get(clz);

		String fieldName = null;
		try {
			fieldName = parsed.getKey(X.KEY_SHARDING);
			if (Objects.isNull(fieldName))
				throw new PersistenceException("No setting of ShardingKey by @X.Sharding");

		} catch (Exception e) {
			e.printStackTrace();
		}

		return fieldName;

	}

	private String getKey(Object obj) {
		Parsed parsed = Parser.get(obj.getClass());

		String value = "";
		Field field = null;
		try {
			field = parsed.getKeyField(X.KEY_SHARDING);
			if (Objects.isNull(field))
				throw new PersistenceException("No setting of ShardingKey by @X.Sharding");
			field.setAccessible(true);
			Object o = field.get(obj);
			value = String.valueOf(o);
		} catch (Exception e) {
			e.printStackTrace();
		}

		String keySharding = parsed.getKey(X.KEY_SHARDING);
		if (Objects.isNull(keySharding))
			throw new PersistenceException("No setting of ShardingKey by @X.Sharding");

		if (value.equals("")) {
			throw new ShardingException("SHARDING VALUE IS NULL, ojb = " + obj);
		}

		String policy = Configs.getString("x7.db.sharding.policy");

		return ShardingPolicy.get(policy).getKey(value);

	}

	private String getKey(long key) {

		String policy = Configs.getString("x7.db.sharding.policy");
		return ShardingPolicy.get(policy).getKey(key);

	}

	private String getKey(String key) {

		String policy = Configs.getString("x7.db.sharding.policy");
		return ShardingPolicy.get(policy).getKey(key);

	}

	@SuppressWarnings({ "rawtypes" })
	private String getKey(Criteria criteria) {
		String key = null;

		Class clz = criteria.getClz();

		String keyFieldName = getKeyFieldName(clz);

		if (StringUtil.isNotNull(keyFieldName)) {
			List<Criteria.X> xList = criteria.getListX();
			Object obj = null;
			for(Criteria.X x : xList){
				if (keyFieldName.equals(x.getKey())){
					obj = x.getValue();
					break;
				}
			}

			if (!Objects.isNull(obj)) {
				if (obj.getClass() == long.class || obj instanceof Long) {
					key = getKey(Long.valueOf(obj.toString()).longValue());
				} else {
					key = getKey(obj.toString());
				}
			}
		}

		return key;
	}

	private Connection getConnectionForMasterId(String key) throws SQLException {
		DataSource dataSource = dsWMap.get(key);
		return getConnection(dataSource);
	}

	private Connection getConnection(String key, boolean isRead) throws SQLException {

		if (dsRMap == null || dsRMap.isEmpty()) {// ONLY WRITE

			if (!isRead) {
				if (!Tx.isNoBizTx()) {
					Connection connection = Tx.getConnection();
					if (connection == null) {
						DataSource dataSource = dsWMap.get(key);
						if (dataSource == null) {
							throw new RollbackException("No DataSource");
						}
						connection = getConnection(dataSource);
						Tx.add(connection);
					}
					return connection;
				}
			}
			DataSource dataSource = dsWMap.get(key);
			if (dataSource == null) {
				throw new RollbackException("No DataSource");
			}
			return getConnection(dataSource);
		}

		if (isRead) {// READ
			DataSource dataSource = dsRMap.get(key);
			return getConnection(dataSource);
		}

		if (!Tx.isNoBizTx()) {
			Connection connection = Tx.getConnection();
			if (connection == null) {
				DataSource dataSource = dsWMap.get(key);
				if (dataSource == null) {
					throw new RollbackException("No DataSource");
				}
				connection = getConnection(dataSource);
				Tx.add(connection);
			}
			return connection;
		}

		DataSource dataSource = dsWMap.get(key);
		if (dataSource == null) {
			throw new RollbackException("No DataSource");
		}
		return getConnection(dataSource);
	}

	private Connection getConnection(DataSource ds) throws SQLException {
		Connection c = ds.getConnection();

		if (c == null) {
			try {
				TimeUnit.MICROSECONDS.sleep(5);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return ds.getConnection();
		}

		return c;
	}

	/**
	 * 放回连接池,<br>
	 * 连接池已经重写了关闭连接的方法
	 */
	private static void close(Connection conn) {
		try {
			if (conn != null) {
				conn.close();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private static void close(PreparedStatement pstmt) {
		if (pstmt != null) {
			try {
				pstmt.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	private void tryToParse(Class clz) {
		Parsed parsed = Parser.get(clz);
	}

	private long create(Object obj, String key) {

		Connection conn = null;
		try {
			conn = getConnection(key, false);
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}

		long id = DaoImpl.getInstance().create(obj, conn);
		return id;
	}

	@Override
	public long create(Object obj) {

		tryToParse(obj.getClass());
		String key = getKey(obj);
		return create(obj, key);
	}

	private boolean refresh(Object obj, String key) {

		Connection conn = null;
		try {
			conn = getConnection(key, false);
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}

		boolean flag = DaoImpl.getInstance().refresh(obj, conn);
		return flag;

	}

	@Override
	public boolean refresh(Object obj) {
		tryToParse(obj.getClass());
		String key = getKey(obj);
		return refresh(obj, key);
	}

	private boolean refresh(Object obj, Map<String, Object> conditionMap, String key) {

		Connection conn = null;
		try {
			conn = getConnection(key, false);
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}

		boolean flag = DaoImpl.getInstance().refresh(obj, conditionMap, conn);
		return flag;

	}

	@Override
	public boolean refresh(Object obj, Map<String, Object> conditionMap) {
		tryToParse(obj.getClass());
		String key = getKey(obj);
		return refresh(obj, conditionMap, key);
	}

	private boolean remove(Object obj, String key) {

		Connection conn = null;
		try {
			conn = getConnection(key, false);
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}

		boolean flag = DaoImpl.getInstance().remove(obj, conn);
		return flag;
	}

	@Override
	public boolean remove(Object obj) {
		tryToParse(obj.getClass());
		String key = getKey(obj);
		return remove(obj, key);
	}

	@Override
	public boolean execute(Object obj, String sql) {
		tryToParse(obj.getClass());
		String key = getKey(obj);

		return false;
	}

	private <T> T get(Class<T> clz, long idOne, String key) {

		Connection conn = null;
		try {
			conn = getConnection(key, true);// FIXME true, need a policy
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}

		T t = DaoImpl.getInstance().get(clz, idOne, conn);
		return t;
	}

	@Override
	public <T> T get(Class<T> clz, long idOne) {

		tryToParse(clz);
		String key = getKey(idOne);

		return get(clz, idOne, key);
	}

	private <T> T getOne(T conditionObj, String key) {

		Connection conn = null;
		try {
			conn = getConnection(key, true);// FIXME true, need a policy
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}

		T t = DaoImpl.getInstance().getOne(conditionObj, conn);
		return t;
	}

	@Override
	public <T> T getOne(T conditionObj) {

		tryToParse(conditionObj.getClass());
		String key = getKey(conditionObj);

		return getOne(conditionObj, key);
	}

	private <T> Pagination<T> find(Criteria criteria, String key) {
		Connection conn = null;
		try {
			conn = getConnection(key, true);// FIXME true, need a policy
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}

		Pagination<T> p = DaoImpl.getInstance().find(criteria,  conn);
		return p;
	}

	@Override
	public <T> Pagination<T> find(Criteria criteria) {

		tryToParse(criteria.getClz());

		String key = getKey(criteria);

		if (StringUtil.isNotNull(key)) {
			return find(criteria, key);
		}

		String policy = Configs.getString("x7.db.sharding.policy");
		String[] keyArr = ShardingPolicy.get(policy).getSuffixArr();

		Map<String, Pagination<T>> resultMap = new HashMap<>();

		/*
		 * map script
		 */
		
		int page = criteria.getPage();
		int rows = criteria.getRows();
		
		Pagination<T> pagination = new Pagination<T>();
		pagination.setOrderBy(criteria.getOrderBy());
		pagination.setDirection(criteria.getDirection());


		Map<String, Future<Pagination<T>>> futureMap = new HashMap<>();

		for (String k : keyArr) {

			Callable<Pagination<T>> task = new Callable<Pagination<T>>() {

				@Override
				public Pagination<T> call() throws Exception {

					Pagination<T> p = null;
					try {
						criteria.setRows(rows * page);
						criteria.setPage(1);
						p = find(criteria,  k);
					} catch (Exception e) {
						for (Future<Pagination<T>> f : futureMap.values()) {
							f.cancel(true);
						}
						throw new PersistenceException("Exception occured while query from sharding DB: " + k);
					}
					return p;

				}

			};

			Future<Pagination<T>> future = service.submit(task);
			futureMap.put(k, future);

		}

		/*
		 * reduce script
		 */
		Set<Entry<String, Future<Pagination<T>>>> entrySet = futureMap.entrySet();
		for (Entry<String, Future<Pagination<T>>> entry : entrySet) {
			String k = entry.getKey();
			Future<Pagination<T>> future = entry.getValue();
			try {
				Pagination<T> p = future.get(2, TimeUnit.MINUTES);
				resultMap.put(k, p);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				for (Future<Pagination<T>> f : futureMap.values()) {
					f.cancel(true);
				}
				throw new PersistenceException("DB is busy, while query from sharding DB: " + k);
			}
		}

		long totalRows = 0;
		List<T> resultList = new ArrayList<>();
		for (Pagination<T> p : resultMap.values()) {
			resultList.addAll(p.getList());
			totalRows += p.getTotalRows();
		}

		String orderBy = criteria.getOrderBy();
		Direction direction = criteria.getDirection();
		if (StringUtil.isNullOrEmpty(orderBy)) {
			Parsed parsed = Parser.get(criteria.getClz());
			orderBy = parsed.getKey(X.KEY_ONE);
			if (Objects.isNull(orderBy))
				throw new PersistenceException("No setting of PrimaryKey by @X.Key");
		}
		if (Objects.isNull(direction)) {
			direction = Direction.DESC;
		}

		Class clz = criteria.getClz();
		BeanUtilX.sort(clz, resultList, orderBy, direction.equals(Direction.ASC));

		resultList = resultList.subList(rows * (page - 1), rows * page);

		pagination.setTotalRows(totalRows);
		pagination.setList(resultList);
		pagination.setRows(rows);
		pagination.setPage(page);

		return pagination;
	}

	private Pagination<Map<String, Object>> find(Criteria.ResultMapped criterionJoinable,
			String key) {
		Connection conn = null;
		try {
			conn = getConnection(key, true);// FIXME true, need a policy
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}

		Pagination<Map<String, Object>> p = DaoImpl.getInstance().find(criterionJoinable, conn);
		return p;
	}

	@Override
	public Pagination<Map<String, Object>> find(Criteria.ResultMapped resultMapped) {

		String key = getKey(resultMapped);

		if (StringUtil.isNotNull(key)) {
			return find(resultMapped, key);
		}

		String policy = Configs.getString("x7.db.sharding.policy");
		String[] keyArr = ShardingPolicy.get(policy).getSuffixArr();

		Map<String, Pagination<Map<String, Object>>> resultMap = new HashMap<>();

		/*
		 * map script
		 */
		final int page = resultMapped.getPage();
		final int rows = resultMapped.getRows();
		Pagination<Map<String, Object>> pagination = new Pagination<>();
		pagination.setOrderBy(resultMapped.getOrderBy());
		pagination.setDirection(resultMapped.getDirection());
		Map<String, Future<Pagination<Map<String, Object>>>> futureMap = new HashMap<>();

		for (String k : keyArr) {

			Callable<Pagination<Map<String, Object>>> task = new Callable<Pagination<Map<String, Object>>>() {

				@Override
				public Pagination<Map<String, Object>> call() throws Exception {

					Pagination<Map<String, Object>> p = null;
					try {
						resultMapped.setRows(rows * page);
						resultMapped.setPage(1);
						p = find(resultMapped, k);
					} catch (Exception e) {
						for (Future<Pagination<Map<String, Object>>> f : futureMap.values()) {
							f.cancel(true);
						}
						throw new PersistenceException("Exception occured while query from sharding DB: " + k);
					}

					return p;

				}

			};

			Future<Pagination<Map<String, Object>>> future = service.submit(task);
			futureMap.put(k, future);

		}

		/*
		 * reduce script
		 */
		Set<Entry<String, Future<Pagination<Map<String, Object>>>>> entrySet = futureMap.entrySet();
		for (Entry<String, Future<Pagination<Map<String, Object>>>> entry : entrySet) {
			String k = entry.getKey();
			Future<Pagination<Map<String, Object>>> future = entry.getValue();
			try {
				Pagination<Map<String, Object>> p = future.get(2, TimeUnit.MINUTES);
				resultMap.put(k, p);
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				for (Future<Pagination<Map<String, Object>>> f : futureMap.values()) {
					f.cancel(true);
				}
				throw new PersistenceException("DB is busy, while query from sharding DB: " + k);
			}
		}

		long totalRows = 0;
		List<Map<String, Object>> resultList = new ArrayList<>();
		for (Pagination<Map<String, Object>> p : resultMap.values()) {
			resultList.addAll(p.getList());
			totalRows += p.getTotalRows();
		}

		String orderBy = resultMapped.getOrderBy();
		Direction direction = resultMapped.getDirection();
		if (StringUtil.isNullOrEmpty(orderBy)) {
			Parsed parsed = Parser.get(resultMapped.getClz());
			orderBy = parsed.getKey(X.KEY_ONE);
			if (Objects.isNull(orderBy))
				throw new PersistenceException("No setting of PrimaryKey by @X.Key");
		}
		if (Objects.isNull(direction)) {
			direction = Direction.DESC;
		}

		BeanUtilX.sort(resultList, orderBy, direction.equals(Direction.ASC));

		resultList = resultList.subList(rows * (page - 1), rows * page);

		pagination.setTotalRows(totalRows);
		pagination.setList(resultList);
		pagination.setPage(page);
		pagination.setRows(rows);

		return pagination;
	}

	private Object reduce(Criteria.ReduceType type, String property, Criteria criteria, String key) {
		Connection conn = null;
		try {
			conn = getConnection(key, true);// FIXME true, need a policy
		} catch (SQLException e) {
			throw new RuntimeException("NO CONNECTION");
		}
		return DaoImpl.getInstance().reduce(type, property, criteria, conn);
	}

	@Override
	public Object reduce(Criteria.ReduceType type, String property, Criteria criteria) {

		tryToParse(criteria.getClz());

		String key = getKey(criteria);

		if (StringUtil.isNotNull(key)) {
			return reduce(type, property, criteria, key);
		}

		String policy = Configs.getString("x7.db.sharding.policy");
		String[] keyArr = ShardingPolicy.get(policy).getSuffixArr();

		/*
		 * map script
		 */
		Map<String, Future<Object>> futureMap = new HashMap<>();

		for (String k : keyArr) {

			Callable<Object> task = new Callable<Object>() {

				@Override
				public Object call() throws Exception {

					Object result = null;
					try {
						result = reduce(type,property, criteria, k);
					} catch (Exception e) {
						for (Future<Object> f : futureMap.values()) {
							f.cancel(true);
						}
						throw new PersistenceException("Exception occured while query from sharding DB: " + k);
					}
					return result;

				}

			};

			Future<Object> future = service.submit(task);
			futureMap.put(k, future);

		}

		/*
		 * reduce script
		 */
		BigDecimal bd = new BigDecimal(0);//sum
		long l = 0;//count
		Set<Entry<String, Future<Object>>> entrySet = futureMap.entrySet();
		for (Entry<String, Future<Object>> entry : entrySet) {
			String k = entry.getKey();
			Future<Object> future = entry.getValue();
			try {
				Object r = future.get(2, TimeUnit.MINUTES);
				if (type == Criteria.ReduceType.COUNT) {
					l += Long.valueOf(r.toString());
				}else if (type == Criteria.ReduceType.SUM){
					bd.add(new BigDecimal(r.toString()));
				}else if (type == Criteria.ReduceType.MAX){
					//FIXME
				}else if (type == Criteria.ReduceType.MIN){
					//FIXME
				}
			} catch (InterruptedException | ExecutionException | TimeoutException e) {
				for (Future<Object> f : futureMap.values()) {
					f.cancel(true);
				}
				throw new PersistenceException("DB is busy, while query from sharding DB: " + k);
			}
		}

		if (type == Criteria.ReduceType.COUNT)
			return l;
		return bd;
	}

}

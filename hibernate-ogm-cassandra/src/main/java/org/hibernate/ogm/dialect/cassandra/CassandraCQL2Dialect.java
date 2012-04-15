/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.hibernate.ogm.dialect.cassandra;

import org.hibernate.HibernateException;
import org.hibernate.LockMode;
import org.hibernate.dialect.lock.LockingStrategy;
import org.hibernate.id.IntegralDataTypeHolder;
import org.hibernate.ogm.datastore.cassandra.impl.CassandraDatastoreProvider;
import org.hibernate.ogm.datastore.impl.MapBasedTupleSnapshot;
import org.hibernate.ogm.datastore.mapbased.impl.MapAssociationSnapshot;
import org.hibernate.ogm.datastore.spi.Association;
import org.hibernate.ogm.datastore.spi.Tuple;
import org.hibernate.ogm.datastore.spi.TupleOperation;
import org.hibernate.ogm.dialect.GridDialect;
import org.hibernate.ogm.dialect.cassandra.impl.ResultSetAssociationSnapshot;
import org.hibernate.ogm.dialect.cassandra.impl.ResultSetTupleSnapshot;
import org.hibernate.ogm.grid.AssociationKey;
import org.hibernate.ogm.grid.EntityKey;
import org.hibernate.ogm.grid.RowKey;
import org.hibernate.ogm.type.GridType;
import org.hibernate.persister.entity.Lockable;
import org.hibernate.type.Type;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uses CQL syntax v2
 *
 * @author Emmanuel Bernard <emmanuel@hibernate.org>
 */
public class CassandraCQL2Dialect implements GridDialect {

	private final CassandraDatastoreProvider provider;
	private final String keyspace;

	public CassandraCQL2Dialect(CassandraDatastoreProvider provider) {
		this.provider = provider;
		this.keyspace = this.provider.getKeyspace();
	}

	@Override
	public LockingStrategy getLockingStrategy(Lockable lockable, LockMode lockMode) {
		//Cassandra essentially has no workable lock strategy unless you use external tools like
		// ZooKeeper or any kind of lock keeper
		// FIXME find a way to reflect that in the implementation
		return null;
	}

	@Override
	public Tuple getTuple(EntityKey key) {
		this.provider.executeStatement( "USE " + this.keyspace + ";", "Unable to switch to keyspace " + this.keyspace );

		String table = key.getTable();
		String s = Arrays.asList( key.getColumnNames() ).toString();
		String idColumnName = s.substring( 1 ).substring( 0, s.length() - 2 );

		StringBuilder query = new StringBuilder()
				.append( "CREATE TABLE " )
				.append( key.getTable() )
				.append( " (" )
				.append( "id varchar PRIMARY KEY" )
//				.append( idColumnName + " varchar PRIMARY KEY" )
//				.append( "KEY varchar PRIMARY KEY," )
//				.append( " id varchar" )
				.append( ");" );

		try {
			this.provider.executeStatement( query.toString(), "Unable create table " + key.getTable() );
		} catch (HibernateException e) {
			//TODO / FIXME already done??
		}
//			DatabaseMetaData metaData = this.provider.getConnection().getMetaData();
//			ResultSet res = provider.getConnection().getMetaData().getTables( "Keyspace1", "", "", new String[0] );
//			res.getMetaData().getTableName( 0 );
//			System.out.println( metaData );

//		query = new StringBuilder()
//				.append( "CREATE INDEX id_key_" + key.getTable() + " ON " )
//				.append(key.getTable())
//				.append( " (id);" );

//		this.provider.executeStatement( query.toString(), "Unable update table " + key.getTable() );

		//FIXME : hack: issue with commit?
		try {
			this.provider.getConnection().close();
		}
		catch (SQLException e) {
			e.printStackTrace();
		}

		this.provider.start();

		//NOTE: SELECT ''..'' returns all columns except the key
		StringBuilder query2 = new StringBuilder( "SELECT * " )
				.append( "FROM " ).append( table )
//				.append( " WHERE " ).append( idColumnName )
				.append( " WHERE " ).append( "id" )
				.append( "=?" );

		ResultSet resultSet;
		boolean next;
		try {
			PreparedStatement statement = provider.getConnection().prepareStatement( query2.toString() );
			String x = Arrays.asList( key.getColumnValues() ).toString();
			String idColumnValue = x.substring( 1 ).substring( 0, x.length() - 2 );
			statement.setString( 1, idColumnValue );
//			statement.setBytes( 1, SerializationHelper.toByteArray( key.getColumnValues() ) );

			statement.execute();
			resultSet = statement.getResultSet();
			//FIXME close statement when done with resultset: Cassandra's driver is cool with that though
			statement.close();
		}
		catch (SQLException e) {
			throw new HibernateException( "Cannot execute select query in cassandra", e );
		}
		try {
			next = resultSet.next();
		}
		catch (SQLException e) {
			throw new HibernateException( "Error while reading resultset", e );
		}
		if ( next == false ) {
			//FIXME Cassandra CQL/JDBC driver return a pseudo row even if the entity does not exists
			// see https://github.com/hibernate/hibernate-ogm/pull/50#issuecomment-4391896
			return null;
		}
		else {
			Tuple tuple = new Tuple( new ResultSetTupleSnapshot( resultSet ) );
			if (tuple.getColumnNames().size() == 1) {
				return null;
			} else {
				return tuple;
			}
		}
	}

	@Override
	public Tuple createTuple(EntityKey key) {
		return new Tuple( new MapBasedTupleSnapshot( new HashMap<String, Object>() ) );
	}

	@Override
	public void updateTuple(Tuple tuple, EntityKey key) {
		this.provider.executeStatement( "USE " + this.keyspace + ";", "Unable to switch to keyspace " + this.keyspace );

		String table = key.getTable(); // FIXME with key.getTable();
		String s = Arrays.asList( key.getColumnNames() ).toString();
		String idColumnName = s.substring( 1 ).substring( 0, s.length() - 2 );

//		String table = "GenericTable"; // FIXME with key.getTable();
//		String idColumnName = "key"; //FIXME extract from key but not present today
		//NOTE: SELECT ''..'' returns all columns except the key

		StringBuilder query = new StringBuilder();
//		query.append( "BEGIN BATCH;" );

		List<TupleOperation> updateOps = new ArrayList<TupleOperation>( tuple.getOperations().size() );
		List<TupleOperation> deleteOps = new ArrayList<TupleOperation>( tuple.getOperations().size() );

		for ( TupleOperation op : tuple.getOperations() ) {
			switch ( op.getType() ) {
				case PUT:
					updateOps.add( op );
					break;
				case REMOVE:
				case PUT_NULL:
					deleteOps.add( op );
					break;
				default:
					throw new HibernateException( "TupleOperation not supported: " + op.getType() );
			}

		}
		StringBuilder keyList = new StringBuilder(  );
		StringBuilder valueList = new StringBuilder(  );
		for ( TupleOperation op : updateOps ) {
			keyList.append( op.getColumn() ).append(",") ;
			valueList.append("'").append(op.getValue() ).append("'").append( "," );
		}
		query.append( "INSERT INTO " )
				.append( key.getTable() )
				.append( "(" )
				.append( keyList.substring( 0, keyList.length() - 1 ) )
				.append( ") VALUES(" )
				.append( valueList.substring( 0, valueList.length() - 1 ) )
				.append( ")" );
		this.provider.executeStatement(
				query.toString(),
				"unable to insert " + "value" + " from " + key.getTable()
		);

//				query.append( "UPDATE " ).append( table ).append( " SET " )
//						// column=?
//						.append( key.getTable() )
//						//TODO Finish this column=?
//						.append( " WHERE " ).append( idColumnName )
//						.append( "=?" );

//		}
		if ( deleteOps.size() > 0 ) {
			query.append( "DELETE " )
					// column
					//TODO Finish this column
					.append( " FROM " ).append( table )
					.append( " WHERE " ).append( idColumnName )
					.append( "=?" );
		}
//		query.append( "APPLY BATCH;" );
	}

	@Override
	public void removeTuple(EntityKey key) {
		//To change body of implemented methods use File | Settings | File Templates.
	}

	@Override
	public Association getAssociation(AssociationKey key) {
		this.provider.executeStatement( "USE " + this.keyspace + ";", "Unable to switch to keyspace " + this.keyspace );

		StringBuilder query = new StringBuilder()
				.append( "CREATE TABLE " )
				.append( key.getTable() )
				.append( " (" )
						//				.append( "id varchar PRIMARY KEY" )
				.append( key.getTable() + "_id varchar PRIMARY KEY" )
						//				.append( "KEY varchar PRIMARY KEY," )
						//				.append( " id varchar" )
				.append( ");" );
		try {
			this.provider.executeStatement( query.toString(), "Unable create table " + key.getTable() );
		} catch (HibernateException e) {
			//TODO / FIXME already created??
		}


//		query = new StringBuilder()
//				.append( "CREATE INDEX id_key_" + key.getColumnNames()[0] + " ON " )
//				.append(key.getTable())
//				.append( " (")
//				.append(key.getColumnNames()[0])
//				.append( ");" );
//
//		this.provider.executeStatement( query.toString(), "Unable update table " + key.getTable() );

		//FIXME : hack: issue with commit?
		try {
			this.provider.getConnection().close();
		}
		catch (SQLException e) {
			e.printStackTrace();
		}

		this.provider.start();

		//NOTE: SELECT ''..'' returns all columns except the key
		StringBuilder query2 = new StringBuilder( "SELECT * " )
				.append( "FROM " ).append( key.getTable() )
				.append( " WHERE " ).append( key.getTable() + "_id" )
//				.append( " WHERE " ).append( "id" )
				.append( "=?" );

		ResultSet resultSet;
		boolean next;
		try {
			PreparedStatement statement = provider.getConnection().prepareStatement( query2.toString() );
			statement.setString( 1, key.getColumnValues()[0].toString() );

			statement.execute();
			resultSet = statement.getResultSet();
			//FIXME close statement when done with resultset: Cassandra's driver is cool with that though
			statement.close();
		}
		catch (SQLException e) {
			throw new HibernateException( "Cannot execute select query in cassandra", e );
		}

		try {
			next = resultSet.next();
		}
		catch (SQLException e) {
			throw new HibernateException( "Error while reading resultset", e );
		}
		if ( next == false ) {
			//FIXME Cassandra CQL/JDBC driver return a pseudo row even if the entity does not exists
			// see https://github.com/hibernate/hibernate-ogm/pull/50#issuecomment-4391896
			return null;
		}
		else {
			Association association = new Association( new ResultSetAssociationSnapshot( resultSet ) );
			if ( association.isEmpty() ) {
				return null;
			}
			else {
				return association;
			}
		}
	}

	@Override
	public Association createAssociation(AssociationKey key) {
		return new Association( new MapAssociationSnapshot( new HashMap<RowKey, Map<String, Object>>() ) ) ;
//
//		               //TODO
//		this.provider.executeStatement( "USE " + this.keyspace + ";", "Unable to switch to keyspace " + this.keyspace );


		//Build Tuple object for row key entry
		// RowKey{table='AccountOwner_BankAccount', columns=[owners_id, bankAccounts_id], columnValues=[c8e14ece-a2c8-4e15-bed9-356d3986b0e7, b54bb9d0-01ec-4b65-a052-bb163367d7f6]} in association AssociationKey{table='AccountOwner_BankAccount'
		// owners_id = 'c8e14ece-a2c8-4e15-bed9-356d3986b0e7'
//		return null;  //To change body of implemented methods use File | Settings | File Templates.
	}

	@Override
	public void updateAssociation(Association association, AssociationKey key) {
		this.provider.executeStatement( "USE " + this.keyspace + ";", "Unable to switch to keyspace " + this.keyspace );


		StringBuilder query = new StringBuilder(  );

		StringBuilder columnIds = new StringBuilder(  );
		StringBuilder columnValues = new StringBuilder(  );

		for (RowKey row : association.getKeys()) {
			columnIds.append(Arrays.asList(row.getColumnNames()));
			columnValues.append( Arrays.asList( row.getColumnValues() ) );
		}
		String columnIdsValue = columnIds.substring( 1, columnIds.length() - 1 );
		String columnValuesValue = columnValues.substring( 1, columnValues.length() - 1 );

		query.append( "INSERT INTO " )
				.append( key.getTable() )
				.append( "(" )
				.append( key.getTable() + "_id," )
				.append( columnIdsValue )
				.append( ") VALUES(" )
				.append( key.getColumnValues()[0].toString() + "," )
				.append( columnValuesValue )
				.append( ");" );
		this.provider.executeStatement(
				query.toString(),
				"unable to insert " + "value" + " from " + key.getTable()
		);

		//To change body of implemented methods use File | Settings | File Templates.
	}

	@Override
	public void removeAssociation(AssociationKey key) {
		//To change body of implemented methods use File | Settings | File Templates.
	}

	@Override
	public Tuple createTupleAssociation(AssociationKey associationKey, RowKey rowKey) {
		return new Tuple( new MapBasedTupleSnapshot( new HashMap<String, Object>() ) );
	}

	@Override
	public void nextValue(RowKey key, IntegralDataTypeHolder value, int increment, int initialValue) {
		//To change body of implemented methods use File | Settings | File Templates.
	}

	@Override
	public GridType overrideType(Type type) {
		return null;
	}
}

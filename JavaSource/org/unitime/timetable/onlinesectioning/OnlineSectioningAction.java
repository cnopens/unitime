/*
 * UniTime 3.2 (University Timetabling Application)
 * Copyright (C) 2011, UniTime LLC, and individual contributors
 * as indicated by the @authors tag.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along
 * with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
*/
package org.unitime.timetable.onlinesectioning;


/**
 * @author Tomas Muller
 */
public interface OnlineSectioningAction<T> {
	
	public T execute(OnlineSectioningServer server, OnlineSectioningHelper helper);
	
	public String name();
	
	public static enum DataMode {
		TRANSACTION, SESSION, NONE
	}
	
	public DataMode dataMode();
	
	public static enum LockType {
		WRITE, READ, NONE
	}
	
	public LockType lockType();

	public abstract class DatabaseAction<T> implements OnlineSectioningAction<T>{

		@Override
		public DataMode dataMode() {
			return DataMode.TRANSACTION;
		}

		@Override
		public LockType lockType() {
			return LockType.WRITE;
		}
	}

	public abstract class SolverAction<T> implements OnlineSectioningAction<T>{

		@Override
		public DataMode dataMode() {
			return DataMode.NONE;
		}

		@Override
		public LockType lockType() {
			return LockType.NONE;
		}
	}
}
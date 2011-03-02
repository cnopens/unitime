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

import java.util.Hashtable;
import java.util.Iterator;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.log4j.Logger;
import org.unitime.timetable.ApplicationProperties;
import org.unitime.timetable.gwt.shared.SectioningException;
import org.unitime.timetable.model.Session;
import org.unitime.timetable.model.StudentSectioningQueue;
import org.unitime.timetable.model.dao.SessionDAO;
import org.unitime.timetable.onlinesectioning.custom.CustomSectionNames;
import org.unitime.timetable.onlinesectioning.custom.SectionLimitProvider;
import org.unitime.timetable.onlinesectioning.custom.SectionUrlProvider;

/**
 * @author Tomas Muller
 */
public class OnlineSectioningService {
	private static Logger sLog = Logger.getLogger(OnlineSectioningService.class);
	private static Hashtable<Long, OnlineSectioningServer> sInstances = new Hashtable<Long, OnlineSectioningServer>();
	private static Hashtable<Long, OnlineSectioningServerUpdater> sUpdaters = new Hashtable<Long, OnlineSectioningServerUpdater>();
	
    public static CustomSectionNames sCustomSectionNames = null;
    public static SectionLimitProvider sSectionLimitProvider = null;
    public static SectionUrlProvider sSectionUrlProvider = null;
    public static boolean sUpdateLimitsUsingSectionLimitProvider = false;
    
	private static ReentrantReadWriteLock sGlobalLock = new ReentrantReadWriteLock();

	public static void init() {
        if (ApplicationProperties.getProperty("unitime.custom.CourseSectionNames") != null) {
        	try {
        		sCustomSectionNames = (CustomSectionNames)Class.forName(ApplicationProperties.getProperty("unitime.custom.CourseSectionNames")).newInstance();
        	} catch (Exception e) {
        		sLog.fatal("Unable to initialize custom section names, reason: "+e.getMessage(), e);
        	}
        }
        if (ApplicationProperties.getProperty("unitime.custom.SectionLimitProvider") != null) {
        	try {
        		sSectionLimitProvider = (SectionLimitProvider)Class.forName(ApplicationProperties.getProperty("unitime.custom.SectionLimitProvider")).newInstance();
        	} catch (Exception e) {
        		sLog.fatal("Unable to initialize section limit provider, reason: "+e.getMessage(), e);
        	}
        }
        if (ApplicationProperties.getProperty("unitime.custom.SectionUrlProvider") != null) {
        	try {
        		sSectionUrlProvider = (SectionUrlProvider)Class.forName(ApplicationProperties.getProperty("unitime.custom.SectionUrlProvider")).newInstance();
        	} catch (Exception e) {
        		sLog.fatal("Unable to initialize section URL provider, reason: "+e.getMessage(), e);
        	}
        }
        sUpdateLimitsUsingSectionLimitProvider = "true".equalsIgnoreCase(ApplicationProperties.getProperty("unitime.custom.SectionLimitProvider.updateLimits", "false"));
	}

	public static boolean isEnabled() {
		// if autostart is enabled, just check whether there are some instances already loaded in
		if ("true".equals(ApplicationProperties.getProperty("unitime.enrollment.autostart", "false")))
			return !sInstances.isEmpty();
		
		// quick check for existing instances
		if (!sInstances.isEmpty()) return true;
		
		// otherwise, look for a session that has sectioning enabled
		String year = ApplicationProperties.getProperty("unitime.enrollment.year");
		String term = ApplicationProperties.getProperty("unitime.enrollment.term");
		for (Iterator<Session> i = SessionDAO.getInstance().findAll().iterator(); i.hasNext(); ) {
			final Session session = i.next();
			
			if (year != null && !year.equals(session.getAcademicYear())) continue;
			if (term != null && !term.equals(session.getAcademicTerm())) continue;

			if (!session.getStatusType().canSectioningStudents()) continue;

			return true;
		}
		return false;
	}
	
	public static boolean isRegistrationEnabled() {
		for (Session session: SessionDAO.getInstance().findAll()) {
			if (!session.getStatusType().canSectioningStudents() && session.getStatusType().canPreRegisterStudents()) return true;
		}
		return false;
	}

	public static void createInstance(Long academicSessionId) {
		sGlobalLock.writeLock().lock();
		try {
			OnlineSectioningServer s = new OnlineSectioningServerImpl(academicSessionId);
			sInstances.put(academicSessionId, s);
			if (sCustomSectionNames != null)
				sCustomSectionNames.update(s.getAcademicSession());
			org.hibernate.Session hibSession = SessionDAO.getInstance().createNewSession();
			try {
				OnlineSectioningServerUpdater updater = new OnlineSectioningServerUpdater(s.getAcademicSession(), StudentSectioningQueue.getLastTimeStamp(hibSession, academicSessionId));
				sUpdaters.put(academicSessionId, updater);
				updater.start();
			} finally {
				hibSession.close();
			}
		} finally {
			sGlobalLock.writeLock().unlock();
		}
	}
	
	public static OnlineSectioningServer getInstance(final Long academicSessionId) throws SectioningException {
		sGlobalLock.readLock().lock();
		try {
			return sInstances.get(academicSessionId);
		} finally {
			sGlobalLock.readLock().unlock();
		}
	}
	
	public static TreeSet<AcademicSessionInfo> getAcademicSessions() {
		sGlobalLock.readLock().lock();
		try {
			TreeSet<AcademicSessionInfo> ret = new TreeSet<AcademicSessionInfo>();
			for (OnlineSectioningServer s : sInstances.values())
				ret.add(s.getAcademicSession());
			return ret;
		} finally {
			sGlobalLock.readLock().unlock();
		}
	}
	
	public static void unload(Long academicSessionId) {
		sGlobalLock.writeLock().lock();
		try {
			OnlineSectioningServerUpdater u = sUpdaters.get(academicSessionId);
			if (u != null)
				u.stopUpdating();
			sInstances.remove(academicSessionId);
			sUpdaters.remove(academicSessionId);
		} finally {
			sGlobalLock.writeLock().unlock();
		}
	}
	
	public static void unloadAll() {
		sGlobalLock.writeLock().lock();
		try {
			for (OnlineSectioningServerUpdater u: sUpdaters.values())
				u.stopUpdating();
			sInstances.clear();
		} finally {
			sGlobalLock.writeLock().unlock();
		}
	}
}
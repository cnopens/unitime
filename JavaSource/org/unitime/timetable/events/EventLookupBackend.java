/*
 * UniTime 3.4 (University Timetabling Application)
 * Copyright (C) 2012, UniTime LLC, and individual contributors
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
package org.unitime.timetable.events;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.unitime.timetable.ApplicationProperties;
import org.unitime.timetable.gwt.command.client.GwtRpcResponseList;
import org.unitime.timetable.gwt.command.server.GwtRpcHelper;
import org.unitime.timetable.gwt.command.server.GwtRpcImplementation;
import org.unitime.timetable.gwt.server.Query;
import org.unitime.timetable.gwt.shared.EventException;
import org.unitime.timetable.gwt.shared.EventInterface;
import org.unitime.timetable.gwt.shared.PageAccessException;
import org.unitime.timetable.gwt.shared.EventInterface.EventFilterRpcRequest;
import org.unitime.timetable.gwt.shared.EventInterface.MeetingInterface;
import org.unitime.timetable.gwt.shared.EventInterface.EventLookupRpcRequest;
import org.unitime.timetable.gwt.shared.EventInterface.ResourceInterface;
import org.unitime.timetable.gwt.shared.EventInterface.ResourceType;
import org.unitime.timetable.model.ClassEvent;
import org.unitime.timetable.model.ClassInstructor;
import org.unitime.timetable.model.Class_;
import org.unitime.timetable.model.CourseOffering;
import org.unitime.timetable.model.Department;
import org.unitime.timetable.model.DepartmentalInstructor;
import org.unitime.timetable.model.Event;
import org.unitime.timetable.model.ExamEvent;
import org.unitime.timetable.model.ExamOwner;
import org.unitime.timetable.model.Location;
import org.unitime.timetable.model.Meeting;
import org.unitime.timetable.model.Roles;
import org.unitime.timetable.model.Session;
import org.unitime.timetable.model.dao.ClassEventDAO;
import org.unitime.timetable.model.dao.DepartmentDAO;
import org.unitime.timetable.model.dao.EventDAO;
import org.unitime.timetable.model.dao.ExamEventDAO;
import org.unitime.timetable.model.dao.SessionDAO;
import org.unitime.timetable.util.Constants;

public class EventLookupBackend implements GwtRpcImplementation<EventLookupRpcRequest, GwtRpcResponseList<EventInterface>>{

	@Override
	public GwtRpcResponseList<EventInterface> execute(EventLookupRpcRequest request, GwtRpcHelper helper) {
		checkAccess(request, helper);
		
		if (request.getEventFilter() == null) {
			EventFilterRpcRequest eventFilter = new EventFilterRpcRequest();
			eventFilter.setSessionId(request.getSessionId());
			request.setEventFilter(eventFilter);
		}
		
		if (helper.getUser() != null) {
			request.getEventFilter().setOption("user", helper.getUser().getId());
			if (request.getRoomFilter() != null)
				request.getRoomFilter().setOption("user", helper.getUser().getId());
			if (helper.getUser().getRole() != null) {
				request.getEventFilter().setOption("role", helper.getUser().getRole());
				if (request.getRoomFilter() != null)
					request.getRoomFilter().setOption("role", helper.getUser().getRole());
			}
		}

		return findEvents(request);
	}
	
	public void checkAccess(EventLookupRpcRequest request, GwtRpcHelper helper) throws PageAccessException {
		if ((request.getResourceType() == ResourceType.PERSON || "true".equals(ApplicationProperties.getProperty("unitime.event_timetable.requires_authentication", "true")))
				&& helper.getUser() == null)
			throw new PageAccessException(request.getResourceType().getPageTitle().substring(0, 1).toUpperCase() +
					request.getResourceType().getPageTitle().substring(1).toLowerCase() + " is only available to authenticated users.");

		if (request.getResourceType() == ResourceType.PERSON) {
			if (!request.hasResourceExternalId()) {
				request.setResourceExternalId(helper.getUser().getId());
			} else {
				if (!request.getResourceExternalId().equals(helper.getUser().getId()) && !(
						Roles.ADMIN_ROLE.equals(helper.getUser().getRole()) ||
						Roles.STUDENT_ADVISOR.equals(helper.getUser().getRole()) ||
						Roles.DEPT_SCHED_MGR_ROLE.equals(helper.getUser().getRole())
						)) {
					if (request.getResourceExternalId() != null && !request.getResourceExternalId().isEmpty() && !request.getResourceExternalId().equals(helper.getUser().getId()))
						throw new EventException("It is not allowed to access a timetable of someone else.");
				}
			}
		}
	}
	
	public GwtRpcResponseList<EventInterface> findEvents(EventLookupRpcRequest request) throws EventException {
		try {
			// EventFilterBackend.
			org.hibernate.Session hibSession = EventDAO.getInstance().getSession();
			boolean suffix = "true".equals(ApplicationProperties.getProperty("tmtbl.exam.report.suffix","false"));
			try {
				Map<Long, Double> distances = new HashMap<Long, Double>();
				if (request.getRoomFilter() != null) {
					for (Location location: new RoomFilterBackend().locations(request.getSessionId(), request.getRoomFilter().getOptions(), new Query(request.getRoomFilter().getText()), 1000, distances, null)) {
						request.getEventFilter().addOption("room", location.getUniqueId().toString());
					}
				}
				EventFilterBackend.EventQuery query = EventFilterBackend.getQuery(request.getEventFilter());
				int limit = request.getLimit();

				List<Meeting> meetings = null;
				Session session = SessionDAO.getInstance().get(request.getSessionId(), hibSession);
				Collection<Long> curriculumCourses = null;
				Collection<Long> curriculumConfigs = null;
				Collection<Long> curriculumClasses = null;
				Department department = null;
				switch (request.getResourceType()) {
				case ROOM:
					if (request.getResourceId() == null)
						meetings = (List<Meeting>)query.select("distinct m").limit(1 + limit).query(hibSession).list();
					else
						meetings = (List<Meeting>)query.select("distinct m")
							.where("l.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.limit(1 + limit)
							.query(hibSession).list();
					break;
				case SUBJECT:
				case COURSE:
					String resourceCheck = (request.getResourceType() == ResourceType.SUBJECT ? "co.subjectArea.uniqueId = :resourceId" : "co.uniqueId = :resourceId");
					
					meetings = new ArrayList<Meeting>();
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ClassEvent")
							.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co")
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co")
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg")
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co")
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co")
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit) 
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg")
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.where(resourceCheck)
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());

					break;			
				case CURRICULUM:
					curriculumCourses = (List<Long>)hibSession.createQuery(
							"select cc.course.uniqueId from CurriculumCourse cc where cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.setLong("resourceId", request.getResourceId()).list();
					
					meetings = new ArrayList<Meeting>();

					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ClassEvent")
							.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("ExamEvent")
							.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg, CurriculumCourse cc")
							.where("co = cc.course")
							.where("cc.classification.curriculum.uniqueId = :resourceId or cc.classification.uniqueId = :resourceId")
							.set("resourceId", request.getResourceId())
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());

					break;
					
				case DEPARTMENT:
					department = DepartmentDAO.getInstance().get(request.getResourceId(), hibSession);
					if (department.isExternalManager()) {
						meetings = (List<Meeting>)query.select("distinct m").type("ClassEvent")
									.from("inner join e.clazz.managingDept d")
									.where("d.uniqueId = :resourceId")
									.set("resourceId", request.getResourceId())
									.limit(1 + limit)
									.query(hibSession).list();
					} else {
						meetings = (List<Meeting>)query.select("distinct m").type("ClassEvent")
								.from("inner join e.clazz.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.limit(1 + limit)
								.query(hibSession).list();
						
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ExamEvent")
								.from("inner join e.exam.owners o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ExamEvent")
								.from("inner join e.exam.owners o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("CourseEvent")
								.from("inner join e.relatedCourses o, Class_ c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("CourseEvent")
								.from("inner join e.relatedCourses o, CourseOffering co inner join co.instructionalOffering.instrOfferingConfigs cfg inner join co.subjectArea.department d")
								.where("d.uniqueId = :resourceId")
								.set("resourceId", request.getResourceId())
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
					}
					
					break;
				case PERSON:
					String role = request.getEventFilter().getOption("role");
					boolean overrideStatus = role != null && (Roles.ADMIN_ROLE.equals(role) || Roles.DEPT_SCHED_MGR_ROLE.equals(role));
					boolean canViewFinalExams = overrideStatus || session.getStatusType().canNoRoleReportExamFinal();
					boolean canViewMidtermExams = overrideStatus || session.getStatusType().canNoRoleReportExamMidterm();
					boolean canViewClasses = overrideStatus || session.getStatusType().canNoRoleReportClass();
					curriculumCourses = new HashSet<Long>();
					curriculumConfigs = new HashSet<Long>();
					curriculumClasses = new HashSet<Long>();
					curriculumCourses.addAll(hibSession.createQuery("select e.courseOffering.uniqueId from StudentClassEnrollment e where e.student.session.uniqueId = :sessionId and e.student.externalUniqueId = :externalId")
							.setLong("sessionId", request.getSessionId())
							.setString("externalId", request.getResourceExternalId()).list());
					curriculumCourses.addAll(hibSession.createQuery("select o.course.uniqueId from Exam x inner join x.owners o inner join x.instructors i where x.session.uniqueId = :sessionId and i.externalUniqueId = :externalId")
							.setLong("sessionId", request.getSessionId())
							.setString("externalId", request.getResourceExternalId()).list());

					meetings = new ArrayList<Meeting>();
					
					if (canViewClasses) {
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ClassEvent").from("inner join e.clazz.studentEnrollments enrl")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type("ClassEvent").from("inner join e.clazz.classInstructors ci")
								.where("ci.instructor.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
					}
					
					if (canViewFinalExams || canViewMidtermExams) {
						String table = (canViewFinalExams ? canViewMidtermExams ? "ExamEvent" : "FinalExamEvent" : "MidtermExamEvent"); 
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.owners o, StudentClassEnrollment enrl inner join enrl.courseOffering co")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.where("o.ownerType = :type and o.ownerId = co.uniqueId")
								.set("type", ExamOwner.sOwnerTypeCourse)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.owners o, StudentClassEnrollment enrl inner join enrl.courseOffering co")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
								.set("type", ExamOwner.sOwnerTypeOffering)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.owners o, StudentClassEnrollment enrl inner join enrl.clazz c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.where("o.ownerType = :type and o.ownerId = c.uniqueId")
								.set("type", ExamOwner.sOwnerTypeClass)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.owners o, StudentClassEnrollment enrl inner join enrl.clazz c inner join c.schedulingSubpart.instrOfferingConfig cfg")
								.where("enrl.student.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
								.set("type", ExamOwner.sOwnerTypeConfig)
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
						
						if (limit <= 0 || meetings.size() < limit)
							meetings.addAll(query.select("distinct m").type(table)
								.from("inner join e.exam.instructors i")
								.where("i.externalUniqueId = :externalId")
								.set("externalId", request.getResourceExternalId())
								.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
								.query(hibSession).list());
					}
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, StudentClassEnrollment enrl inner join enrl.courseOffering co")
							.where("enrl.student.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, StudentClassEnrollment enrl inner join enrl.courseOffering co")
							.where("enrl.student.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, StudentClassEnrollment enrl inner join enrl.clazz c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("enrl.student.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, StudentClassEnrollment enrl inner join enrl.clazz c inner join c.schedulingSubpart.instrOfferingConfig cfg")
							.where("enrl.student.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, ClassInstructor ci inner join ci.classInstructing c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("ci.instructor.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = co.uniqueId")
							.set("type", ExamOwner.sOwnerTypeCourse)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, ClassInstructor ci inner join ci.classInstructing c inner join c.schedulingSubpart.instrOfferingConfig.instructionalOffering.courseOfferings co")
							.where("ci.instructor.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = co.instructionalOffering.uniqueId")
							.set("type", ExamOwner.sOwnerTypeOffering)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, ClassInstructor ci inner join ci.classInstructing c inner join c.schedulingSubpart.instrOfferingConfig cfg")
							.where("ci.instructor.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = cfg.uniqueId")
							.set("type", ExamOwner.sOwnerTypeConfig)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m").type("CourseEvent")
							.from("inner join e.relatedCourses o, ClassInstructor ci inner join ci.classInstructing c")
							.where("ci.instructor.externalUniqueId = :externalId")
							.set("externalId", request.getResourceExternalId())
							.where("o.ownerType = :type and o.ownerId = c.uniqueId")
							.set("type", ExamOwner.sOwnerTypeClass)
							.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
							.query(hibSession).list());


					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m")
                    		.where("e.class in (CourseEvent, SpecialEvent)")
                    		.where("e.mainContact.externalUniqueId = :externalId")
                    		.set("externalId", request.getResourceExternalId())
                    		.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
                    		.query(hibSession).list());
                    
					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m")
                    		.from("inner join m.event.additionalContacts c")
                    		.where("c.externalUniqueId = :externalId")
                    		.set("externalId", request.getResourceExternalId())
                    		.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
                    		.query(hibSession).list());

					if (limit <= 0 || meetings.size() < limit)
						meetings.addAll(query.select("distinct m")
                    		.from("EventContact c")
                    		.where("c.externalUniqueId = :externalId")
                    		.where("c.emailAddress is not null")
                    		.where("lower(m.event.email) like '%' || lower(c.emailAddress) || '%'")
                    		.set("externalId", request.getResourceExternalId())
                    		.limit(limit <= 0 ? -1 : 1 + limit - meetings.size())
                    		.query(hibSession).list());
                    break;
				default:
					throw new EventException("Resource type " + request.getResourceType().getLabel() + " not supported.");
				}
				
				Date now = new Date();
				GwtRpcResponseList<EventInterface> ret = new GwtRpcResponseList<EventInterface>();
				Hashtable<Long, EventInterface> events = new Hashtable<Long, EventInterface>();
				for (Meeting m: meetings) {
					EventInterface event = events.get(m.getEvent().getUniqueId());
					if (event == null) {
						event = new EventInterface();
						event.setId(m.getEvent().getUniqueId());
						event.setName(m.getEvent().getEventName());
						event.setType(m.getEvent().getEventTypeAbbv());
						events.put(m.getEvent().getUniqueId(), event);
						event.setCanView(request.getEventFilter().hasOption("role") || (request.getEventFilter().hasOption("user") && request.getEventFilter().getOption("user").equals(m.getEvent().getMainContact().getExternalUniqueId())));
						event.setCanEdit(event.isCanView() && (
								Roles.ADMIN_ROLE.equals(request.getEventFilter().getOption("role")) || 
								Roles.EVENT_MGR_ROLE.equals(request.getEventFilter().getOption("role")) || 
								request.getEventFilter().getOption("user").equals(m.getEvent().getMainContact().getExternalUniqueId())));
						ret.add(event);
						
						if (m.getEvent().getMainContact() != null)
							event.setContact(
									(m.getEvent().getMainContact().getLastName() == null ? "" : m.getEvent().getMainContact().getLastName() + ", ") +
									(m.getEvent().getMainContact().getFirstName() == null ? "" : m.getEvent().getMainContact().getFirstName()) + 
									(m.getEvent().getMainContact().getMiddleName() == null ? "" : " " + m.getEvent().getMainContact().getMiddleName()));
						
						if (m.getEvent().getSponsoringOrganization() != null) {
							event.setSponsor(m.getEvent().getSponsoringOrganization().getName());
							event.setEmail(m.getEvent().getSponsoringOrganization().getEmail());
						}
						
				    	if (Event.sEventTypeClass == m.getEvent().getEventType()) {
				    		ClassEvent ce = ClassEventDAO.getInstance().get(m.getEvent().getUniqueId(), hibSession);
				    		Class_ clazz = ce.getClazz();
				    		if (clazz.getDisplayInstructor()) {
				    			String instructor = "", email = "";
				    			for (ClassInstructor i: clazz.getClassInstructors()) {
				    				if (!instructor.isEmpty()) { instructor += "|"; email += "|"; }
				    				instructor += Constants.toInitialCase(i.nameLastNameFirst());
				    				email += (i.getInstructor().getEmail() == null ? "-" : i.getInstructor().getEmail());
				    			}
				    			event.setInstructor(instructor);
				    			event.setEmail(email);
				    		}
				    		CourseOffering correctedOffering = clazz.getSchedulingSubpart().getInstrOfferingConfig().getInstructionalOffering().getControllingCourseOffering();
				    		List<CourseOffering> courses = new ArrayList<CourseOffering>(clazz.getSchedulingSubpart().getInstrOfferingConfig().getInstructionalOffering().getCourseOfferings());
				    		switch (request.getResourceType()) {
				    		case SUBJECT:
			    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
			    					CourseOffering co = i.next();
			    					if (co.getSubjectArea().getUniqueId().equals(request.getResourceId())) {
			    						if (!correctedOffering.getSubjectArea().getUniqueId().equals(request.getResourceId()))
			    							correctedOffering = co;
			    					} else {
			    						i.remove();
			    					}
			    				}
				    			break;
				    		case COURSE:
			    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
			    					CourseOffering co = i.next();
			    					if (co.getUniqueId().equals(request.getResourceId())) {
			    						if (!correctedOffering.getUniqueId().equals(request.getResourceId()))
			    							correctedOffering = co;
			    					} else {
			    						i.remove();
			    					}
			    				}
				    			break;
				    		case DEPARTMENT:
				    			if (department.isExternalManager()) break;
			    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
			    					CourseOffering co = i.next();
			    					if (co.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId())) {
			    						if (!correctedOffering.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId()))
			    							correctedOffering = co;
			    					} else {
			    						i.remove();
			    					}
			    				}
				    			break;
				    		case CURRICULUM:
				    		case PERSON:
			    				for (Iterator<CourseOffering> i = courses.iterator(); i.hasNext(); ) {
			    					CourseOffering co = i.next();
			    					if (curriculumCourses.contains(co.getUniqueId())) {
			    						if (!curriculumCourses.contains(correctedOffering.getUniqueId()))
			    							correctedOffering = co;
			    					} else {
			    						i.remove();
			    					}
			    				}
				    			break;
				    		}
				    		courses.remove(correctedOffering);
				    		event.addCourseName(correctedOffering.getCourseName());
				    		event.setInstruction(clazz.getSchedulingSubpart().getItype().getDesc());
				    		event.setInstructionType(clazz.getSchedulingSubpart().getItype().getItype());
				    		String section = (suffix && clazz.getClassSuffix(correctedOffering) != null ? clazz.getClassSuffix(correctedOffering) : clazz.getSectionNumberString(hibSession));
				    		event.addExternalId(section);
				    		if (clazz.getClassSuffix(correctedOffering) == null) {
					    		event.setName(clazz.getClassLabel(correctedOffering));
				    		} else {
				    			event.setName(correctedOffering.getCourseName() + " " + section);
				    		}
			    			for (CourseOffering co: courses) {
					    		event.addCourseName(co.getCourseName());
					    		event.addExternalId(suffix && clazz.getClassSuffix(co) != null ? clazz.getClassSuffix(co) : clazz.getSectionNumberString(hibSession));
			    			}
				    	} else if (Event.sEventTypeFinalExam == m.getEvent().getEventType() || Event.sEventTypeMidtermExam == m.getEvent().getEventType()) {
				    		ExamEvent xe = ExamEventDAO.getInstance().get(m.getEvent().getUniqueId(), hibSession);
			    			String instructor = "", email = "";;
			    			for (DepartmentalInstructor i: xe.getExam().getInstructors()) {
			    				if (!instructor.isEmpty()) { instructor += "|"; email += "|"; }
			    				instructor += Constants.toInitialCase(i.nameLastNameFirst());
			    				email += (i.getEmail() == null ? "" : i.getEmail());
			    			}
			    			event.setInstructor(instructor);
			    			event.setEmail(email);
			    			for (ExamOwner owner: new TreeSet<ExamOwner>(xe.getExam().getOwners())) {
			    				courses: for(CourseOffering course: owner.getCourse().getInstructionalOffering().getCourseOfferings()) {
						    		switch (request.getResourceType()) {
						    		case SUBJECT:
						    			if (!course.getSubjectArea().getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case COURSE:
						    			if (!course.getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case DEPARTMENT:
						    			if (department.isExternalManager()) break courses;
						    			if (!course.getSubjectArea().getDepartment().getUniqueId().equals(request.getResourceId())) continue courses;
						    			break;
						    		case CURRICULUM:
						    			if (!curriculumCourses.contains(course.getUniqueId())) continue courses;
						    			break;
						    		case PERSON:
						    			if (!curriculumCourses.contains(course.getUniqueId())) continue courses;
						    			if (owner.getOwnerType() == ExamOwner.sOwnerTypeClass && !curriculumClasses.contains(owner.getOwnerId())) continue;
						    			if (owner.getOwnerType() == ExamOwner.sOwnerTypeConfig && !curriculumConfigs.contains(owner.getOwnerId())) continue;
						    			break;
						    		}
				    				String courseName = owner.getCourse().getCourseName();
				    				String label = owner.getLabel();
				    				if (label.startsWith(courseName)) {
				    					label = label.substring(courseName.length());
				    				}
				    				event.addCourseName(course.getCourseName());
				    				event.addExternalId(label.trim());
			    				}
			    			}
			    			if (event.hasCourseNames() && event.getCourseNames().size() == 1 && request.getResourceType() == ResourceType.PERSON)
		    					event.setName((event.getCourseNames().get(0) + " " + event.getExternalIds().get(0)).trim());
				    	}
					}
					MeetingInterface meeting = new MeetingInterface();
					meeting.setId(m.getUniqueId());
					meeting.setMeetingDate(new SimpleDateFormat("MM/dd").format(m.getMeetingDate()));
					meeting.setDayOfWeek(Constants.getDayOfWeek(m.getMeetingDate()));
					meeting.setStartTime(m.getStartTime().getTime());
					meeting.setStopTime(m.getStopTime().getTime());
					Calendar c = Calendar.getInstance(Locale.US);
					c.setTime(m.getMeetingDate());
					int dayOfYear = c.get(Calendar.DAY_OF_YEAR);
					int sessionYear = session.getSessionStartYear();
					if (c.get(Calendar.YEAR) < sessionYear) {
						Calendar x = Calendar.getInstance(Locale.US);
					    x.set(c.get(Calendar.YEAR),11,31,0,0,0);
					    dayOfYear -= x.get(Calendar.DAY_OF_YEAR);
					} else if (c.get(Calendar.YEAR) > sessionYear) {
						Calendar x = Calendar.getInstance(Locale.US);
					    x.set(sessionYear,11,31,0,0,0);
					    dayOfYear += x.get(Calendar.DAY_OF_YEAR);
					}
					meeting.setDayOfYear(dayOfYear);
					meeting.setMeetingTime(m.startTime() + " - " + m.stopTime());
					meeting.setStartSlot(m.getStartPeriod());
					meeting.setEndSlot(m.getStopPeriod());
					meeting.setPast(m.getStartTime().before(now));
					if (m.isApproved())
						meeting.setApprovalDate(m.getApprovedDate());
					if (m.getLocation() != null) {
						ResourceInterface location = new ResourceInterface();
						location.setType(ResourceType.ROOM);
						location.setId(m.getLocation().getUniqueId());
						location.setName(m.getLocation().getLabel());
						location.setHint(m.getLocation().getHtmlHint());
						location.setSize(m.getLocation().getCapacity());
						location.setDistance(distances.get(m.getLocation().getUniqueId()));
						location.setRoomType(m.getLocation().getRoomTypeLabel());
						meeting.setLocation(location);
					}
					event.addMeeting(meeting);
				}
				return ret;
			} finally {
				hibSession.close();
			}
		} catch (EventException e) {
			throw e;
		} catch (Exception e) {
			throw new EventException("Unable to find events for " + request.getResourceType().getLabel() + " " + request.getResourceId() + ": " + e.getMessage());
		}
	}

}
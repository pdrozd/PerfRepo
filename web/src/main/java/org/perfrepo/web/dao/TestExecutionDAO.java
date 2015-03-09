/**
 *
 * PerfRepo
 *
 * Copyright (C) 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.perfrepo.web.dao;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.AbstractQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;

import org.perfrepo.model.Metric;
import org.perfrepo.model.Tag;
import org.perfrepo.model.Test;
import org.perfrepo.model.TestExecution;
import org.perfrepo.model.TestExecutionParameter;
import org.perfrepo.model.TestExecutionTag;
import org.perfrepo.model.Value;
import org.perfrepo.model.to.TestExecutionSearchTO;
import org.perfrepo.model.to.MetricReportTO.DataPoint;
import org.perfrepo.model.to.TestExecutionSearchTO.ParamCriteria;
import org.perfrepo.model.userproperty.GroupFilter;
import org.perfrepo.model.util.EntityUtils;
import org.perfrepo.web.util.TagUtils;

/**
 * DAO for {@link TestExecution}
 *
 * @author Pavel Drozd (pdrozd@redhat.com)
 * @author Michal Linhard (mlinhard@redhat.com)
 * @author Jiri Holusa (jholusa@redhat.com)
 */
@Named
public class TestExecutionDAO extends DAO<TestExecution, Long> {

   @Inject
   private TestExecutionParameterDAO testExecutionParameterDAO;

	public List<TestExecution> getByTest(Long testId) {
		Test test = new Test();
		test.setId(testId);
		return getAllByProperty("test", test);
	}

   /**
    * Returns interval of test executions ordered by started date asc. The interval is defined as
    * <number_of_executions - from, number_of_executions - from + howMany>, i.e. behaves exactly as
    * SQL LIMIT clause, goes back in past by <from> executions and from that test execution takes <howMany>
    * following test executions.
    *
    * @param from
    * @param howMany
    * @return
    */
   public List<TestExecution> getLast(int from, int howMany) {
      CriteriaBuilder criteriaBuilder = criteriaBuilder();

      CriteriaQuery<Long> countQuery = criteriaBuilder.createQuery(Long.class);
      countQuery.select(criteriaBuilder.count(countQuery.from(TestExecution.class)));

      Long count = query(countQuery).getSingleResult();

      CriteriaQuery<TestExecution> resultQuery = createCriteria();

      Root<TestExecution> root = resultQuery.from(TestExecution.class);

      resultQuery.select(root);
      resultQuery.orderBy(criteriaBuilder.asc(root.get("started")));

      TypedQuery<TestExecution> typedQuery = query(resultQuery);
      typedQuery.setFirstResult(count.intValue() - from);
      typedQuery.setMaxResults(howMany);

      List<TestExecution> result = typedQuery.getResultList();

      return result;
   }

   /**
    * Returns test executions with property value between selected boundaries. This can be applied only on
    * Comparable values, otherwise the result is undefined.
    *
    * @param propertyName property to be search (filtered) on
    * @param from lower boundary
    * @param to upper boundary
    * @return list of according test executions
    */
   public <T extends Comparable<? super T>> List<TestExecution> getAllByPropertyBetween(String propertyName, T from, T to) {
      CriteriaQuery<TestExecution> resultQuery = createCriteria();
      CriteriaBuilder cb = criteriaBuilder();

      Root<TestExecution> root = resultQuery.from(TestExecution.class);
      resultQuery.select(root);
      resultQuery.where(cb.between(root.<T>get(propertyName), from, to));

      return query(resultQuery).getResultList();
   }

   /**
    * Allows to search test executions by many complex criterias.
    *
    * @param search
    * @param userGroups
    * @return
    */
   public List<TestExecution> searchTestExecutions(TestExecutionSearchTO search, List<String> userGroups) {
      List<String> tags = TagUtils.parseTags(search.getTags() != null ? search.getTags().toLowerCase() : "");
      List<String> excludedTags = new ArrayList<>();
      List<String> includedTags = new ArrayList<>();
      divideTags(tags, includedTags, excludedTags);

      CriteriaQuery<TestExecution> criteria = constructSearchCriteria(search, includedTags, excludedTags);

      TypedQuery<TestExecution> query = query(criteria);
      fillParameterValues(query, search, includedTags, excludedTags, userGroups);

      List<TestExecution> result = query.getResultList();
      List<TestExecution> clonedResult = EntityUtils.clone(result);
      filterResultByParameters(clonedResult, search);

      return clonedResult;
   }

   /**
    * Shortcut for getTestExecutions(tags, testUIDs, null, null)
    *
    * @param tags
    * @param testUIDs
    * @return
    */
   public List<TestExecution> getTestExecutions(List<String> tags, List<String> testUIDs) {
      return getTestExecutions(tags, testUIDs, null, null);
   }

   /**
    * This method retrieves all test executions that belong to one of the specified tests and have
    * ALL the tags. The 'lastFrom' and 'howMany' parameters works as a LIMIT in SQL, e.g. lastFrom = 5, howMany = 3
    * will return 3 last test executions shifted by 2 (last 2 test executions will not be in the result)
    *
    * @param tags ALL tags that test execution must have
    * @param testUIDs ID's of the tests
    * @param lastFrom see comment above. null = all test executions will be retrieved (both lastFrom and howMany must be set to take effect)
    * @param howMany see comment above. null = all test executions will be retrieved (both lastFrom and howMany must be set to take effect)
    * @return
    */
   public List<TestExecution> getTestExecutions(List<String> tags, List<String> testUIDs, Integer lastFrom, Integer howMany) {
      CriteriaBuilder cb = criteriaBuilder();

      Long count = null;
      //we want to you 'last' boundaries, we must compute the number of test executions that
      //match the requirements - have selected tags and belong to selected tests
      if(lastFrom != null && howMany != null) {
         CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
         Root<TestExecution> root = countQuery.from(TestExecution.class);
         countQuery.select(cb.countDistinct(root));

         Subquery<Long> subquery = (Subquery) createSubqueryByTags(countQuery.subquery(Long.class));
         Root<TestExecution> subqueryRoot = (Root<TestExecution>)subquery.getRoots().toArray()[0];
         subquery.select(subqueryRoot.<Long>get("id"));

         countQuery.where(cb.in(root.get("id")).value(subquery));
         TypedQuery<Long> typedCountQuery = createTypedQueryByTags(countQuery, testUIDs, tags);
         count = typedCountQuery.getSingleResult();
      }

      //now we can retrieve the actual result
      CriteriaQuery<TestExecution> criteriaQuery = (CriteriaQuery) createSubqueryByTags(cb.createQuery(TestExecution.class));
      Root<TestExecution> root = (Root<TestExecution>)criteriaQuery.getRoots().toArray()[0];
      criteriaQuery.select(root);
      criteriaQuery.orderBy(cb.asc(root.get("started")));

      TypedQuery<TestExecution> query = createTypedQueryByTags(criteriaQuery, testUIDs, tags);
      //we're using 'last' parameters, set the paging
      if(count != null) {
         int firstResult = count.intValue() - lastFrom;
         query.setFirstResult(firstResult < 0 ? 0 : firstResult);
         query.setMaxResults(howMany);
      }

      List<TestExecution> result = query.getResultList();

      List<TestExecution> resultClone = EntityUtils.clone(result);
      for (TestExecution exec : resultClone) {
         TestExecutionDAO.fetchTest(exec);
         TestExecutionDAO.fetchParameters(exec);
         TestExecutionDAO.fetchTags(exec);
         TestExecutionDAO.fetchValues(exec);
      }

      return resultClone;
   }

	/**
	 * Finds all values used for computing MetricHistory report
	 *
	 * @param testId
	 * @param metricName
	 * @param tagList
	 * @param limitSize
	 * @return List of DataPoint objects
	 */
	public List<DataPoint> searchValues(Long testId, String metricName, List<String> tagList, int limitSize) {
		boolean useTags = tagList != null && !tagList.isEmpty();
		CriteriaBuilder cb = criteriaBuilder();
		CriteriaQuery<DataPoint> criteria = cb.createQuery(DataPoint.class);
		// test executions
		Root<TestExecution> rExec = criteria.from(TestExecution.class);
		// test joined via test exec.
		Join<TestExecution, Test> rTest_Exec = rExec.join("test");
		// values
		Join<TestExecution, Value> rValue = rExec.join("values");
		// metrics
		Join<Value, Metric> rMetric = rValue.join("metric");
		// test joined via metric
		Join<Metric, Test> rTest_Metric = rMetric.join("testMetrics").join("test");
		// tag
		Join<TestExecution, Tag> rTag = null;
		Predicate pTagNameInFixedList = cb.and(); // default for this predicate is true
		Predicate pHavingAllTagsPresent = cb.and();
		if (useTags) {
			rTag = rExec.join("testExecutionTags").join("tag");
			pTagNameInFixedList = rTag.get("name").in(cb.parameter(List.class, "tagList"));
			pHavingAllTagsPresent = cb.ge(cb.count(rTag.get("id")), cb.parameter(Long.class, "tagListSize"));
		}

		Predicate pMetricNameFixed = cb.equal(rMetric.get("name"), cb.parameter(String.class, "metricName"));
		Predicate pTestFixed = cb.equal(rTest_Exec.get("id"), cb.parameter(Long.class, "testId"));
		Predicate pMetricFromSameTest = cb.equal(rTest_Metric.get("id"), rTest_Exec.get("id"));

		//sort by date
		criteria.select(cb.construct(DataPoint.class, rExec.get("started"), rValue.get("resultValue"), rExec.get("id")));
		criteria.where(cb.and(pMetricNameFixed, pTagNameInFixedList, pTestFixed, pMetricFromSameTest));
		criteria.groupBy(rValue.get("resultValue"), rExec.get("id"), rExec.get("started"));
		criteria.orderBy(cb.desc(rExec.get("started")));

		criteria.having(pHavingAllTagsPresent);

		TypedQuery<DataPoint> query = query(criteria);
		query.setParameter("testId", testId);
		query.setParameter("metricName", metricName);

		if (useTags) {
			query.setParameter("tagList", tagList);
			query.setParameter("tagListSize", new Long(tagList.size()));
		}
		query.setMaxResults(limitSize);
		return query.getResultList();
	}

	public Double getValueForMetric(Long execId, String metricName) {
		CriteriaBuilder cb = criteriaBuilder();
		CriteriaQuery<Double> criteria = cb.createQuery(Double.class);
		// test executions
		Root<TestExecution> rExec = criteria.from(TestExecution.class);
		// test joined via test exec.
		Join<TestExecution, Test> rTest_Exec = rExec.join("test");
		// values
		Join<TestExecution, Value> rValue = rExec.join("values");
		// metrics
		Join<Value, Metric> rMetric = rValue.join("metric");
		// test joined via metric
		Join<Metric, Test> rTest_Metric = rMetric.join("testMetrics").join("test");

		Predicate pMetricNameFixed = cb.equal(rMetric.get("name"), cb.parameter(String.class, "metricName"));
		Predicate pExecFixed = cb.equal(rExec.get("id"), cb.parameter(Long.class, "execId"));
		Predicate pMetricFromSameTest = cb.equal(rTest_Metric.get("id"), rTest_Exec.get("id"));
		criteria.multiselect(rValue.get("resultValue"));
		criteria.where(cb.and(pMetricNameFixed, pExecFixed, pMetricFromSameTest));

		TypedQuery<Double> query = query(criteria);
		query.setParameter("execId", execId);
		query.setParameter("metricName", metricName);

		List<Double> r = query.getResultList();
		if (r.isEmpty()) {
			return null;
		} else {
			return r.get(0);
		}
	}

   /**
    * Creates a criteria query of Criteria API for searching of test executions.
    *
    * @param search
    */
   private CriteriaQuery<TestExecution> constructSearchCriteria(TestExecutionSearchTO search, List<String> includedTags, List<String> excludedTags) {
      CriteriaQuery<TestExecution> criteria = createCriteria();
      CriteriaBuilder cb = criteriaBuilder();

      //initialize predicates
      Predicate pStartedFrom = cb.and();
      Predicate pStartedTo = cb.and();
      Predicate pTagNameInFixedList = cb.and();
      Predicate pExcludedTags = cb.and();
      Predicate pTestName = cb.and();
      Predicate pTestUID = cb.and();
      Predicate pTestGroups = cb.and();
      Predicate pParamsMatch = cb.and();
      Predicate pHavingAllTagsPresent = cb.and();

      Root<TestExecution> rExec = criteria.from(TestExecution.class);

      // construct criteria
      if (search.getStartedFrom() != null) {
         pStartedFrom = cb.greaterThanOrEqualTo(rExec.<Date>get("started"), cb.parameter(Date.class, "startedFrom"));
      }
      if (search.getStartedTo() != null) {
         pStartedTo = cb.lessThanOrEqualTo(rExec.<Date>get("started"), cb.parameter(Date.class, "startedTo"));
      }
      if (!includedTags.isEmpty() || !excludedTags.isEmpty()) {
         Join<TestExecution, Tag> rTag = rExec.joinCollection("testExecutionTags").join("tag");
         if (!includedTags.isEmpty()) {
            pTagNameInFixedList = cb.lower(rTag.<String>get("name")).in(cb.parameter(List.class, "tagList"));
            pHavingAllTagsPresent = cb.ge(cb.count(rTag.get("id")), cb.parameter(Long.class, "tagListSize"));
         }

         if (!excludedTags.isEmpty()) {
            Subquery<Long> sq = criteria.subquery(Long.class);
            Root<TestExecution> sqRoot = sq.from(TestExecution.class);
            Join<TestExecution, Tag> sqTag = sqRoot.joinCollection("testExecutionTags").join("tag");
            sq.select(sqRoot.<Long>get("id"));
            sq.where(cb.lower(sqTag.<String>get("name")).in(cb.parameter(List.class, "excludedTagList")));

            pExcludedTags = cb.not(rExec.get("id").in(sq));
         }
      }
      if (search.getTestName() != null && !"".equals(search.getTestName())) {
         Join<TestExecution, Test> rTest = rExec.join("test");
         pTestName = cb.like(cb.lower(rTest.<String>get("name")), cb.parameter(String.class, "testName"));
      }
      if (search.getTestUID() != null && !"".equals(search.getTestUID())) {
         Join<TestExecution, Test> rTest = rExec.join("test");
         pTestUID = cb.like(cb.lower(rTest.<String>get("uid")), cb.parameter(String.class, "testUID"));
      }
      if (GroupFilter.MY_GROUPS.equals(search.getGroupFilter())) {
         Join<TestExecution, Test> rTest = rExec.join("test");
         pTestGroups = cb.and(rTest.<String>get("groupId").in(cb.parameter(List.class, "groupNames")));
      }
      if (search.getParameters() != null && !search.getParameters().isEmpty()) {
         for (int pCount = 1; pCount < search.getParameters().size() + 1; pCount++) {
            Join<TestExecution, TestExecutionParameter> rParam = rExec.join("parameters");
            pParamsMatch = cb.and(pParamsMatch, cb.equal(rParam.get("name"), cb.parameter(String.class, "paramName" + pCount)));
            pParamsMatch = cb.and(pParamsMatch, cb.like(rParam.<String>get("value"), cb.parameter(String.class, "paramValue" + pCount)));
         }
      }

      // construct query
      criteria.select(rExec);
      criteria.where(cb.and(pStartedFrom, pStartedTo, pTagNameInFixedList, pExcludedTags, pTestName, pTestUID, pTestGroups, pParamsMatch));
      criteria.having(pHavingAllTagsPresent);
      // this isn't very elegant, but Postgres 8.4 doesn't allow GROUP BY only with id
      // this feature is allowed only since Postgres 9.1+
      criteria.groupBy(rExec.get("test"), rExec.get("id"), rExec.get("name"), rExec.get("started"), rExec.get("comment"));
      // sorting by started time
      criteria.orderBy(cb.asc(rExec.get("started")));

      return criteria;
   }

   /**
    * After search of test executions with various properties, now we want to filter them
    * according to test execution parameters. This method does the filtering.
    *
    * @param result
    * @param search
    */
   private void filterResultByParameters(List<TestExecution> result, TestExecutionSearchTO search) {
      List<String> displayedParams = null;
      //go through the entered test parameters
      //if the parameter doesn't have the value, add % as the value,
      //i.e. check if the test execution has this parameter
      if (search.getParameters() != null && !search.getParameters().isEmpty()) {
         displayedParams = new ArrayList<String>(search.getParameters().size());
         for (ParamCriteria pc : search.getParameters()) {
            if (pc.isDisplayed()) {
               displayedParams.add(pc.getName());
            }
            if (pc.getValue() == null || "".equals(pc.getValue().trim())) {
               pc.setValue("%");
            }
         }
      }

      List<Long> execIds = EntityUtils.extractIds(result);
      if (displayedParams != null && !displayedParams.isEmpty() && !execIds.isEmpty()) {
         List<TestExecutionParameter> allParams = testExecutionParameterDAO.find(execIds, displayedParams);
         Map<Long, List<TestExecutionParameter>> paramsByExecId = new HashMap<Long, List<TestExecutionParameter>>();

         for (TestExecutionParameter param: allParams) {
            List<TestExecutionParameter> paramListForExec = paramsByExecId.get(param.getTestExecution().getId());
            if (paramListForExec == null) {
               paramListForExec = new ArrayList<TestExecutionParameter>(displayedParams.size());
               paramsByExecId.put(param.getTestExecution().getId(), paramListForExec);
            }

            paramListForExec.add(param);
         }

         for (TestExecution exec: result) {
            List<TestExecutionParameter> paramListForExec = paramsByExecId.get(exec.getId());
            exec.setParameters(paramListForExec == null ? Collections.<TestExecutionParameter>emptyList() : paramListForExec);
            exec.setTestExecutionTags(EntityUtils.clone(exec.getTestExecutionTags()));
            for (TestExecutionTag tet : exec.getTestExecutionTags()) {
               tet.setTag(tet.getTag().clone());
            }
         }
      } else {
         for (TestExecution exec: result) {
            exec.setParameters(Collections.<TestExecutionParameter>emptyList());
            exec.setTestExecutionTags(EntityUtils.clone(exec.getTestExecutionTags()));
            for (TestExecutionTag tet : exec.getTestExecutionTags()) {
               tet.setTag(tet.getTag().clone());
            }
         }
      }
   }

   /**
    * Helper method. Because when trying to retrieve count of test executions according to
    * some restrictions (like tags etc, in general when the query has having, where, group by together) via
    * Criteria API, there is no way to reuse the query even though it differs in two lines.
    *
    * Hence, the basics of the query are extracted to this method to avoid code duplication.
    *
    * @param criteriaQuery
    * @return
    */
   private AbstractQuery createSubqueryByTags(AbstractQuery criteriaQuery) {
      CriteriaBuilder cb = criteriaBuilder();

      AbstractQuery query = criteriaQuery;
      Root<TestExecution> rExec = query.from(TestExecution.class);
      Join<TestExecution, Test> rTest = rExec.join("test");
      Predicate pTestUID = rTest.<String>get("uid").in(cb.parameter(List.class, "testUID"));
      Join<TestExecution, Tag> rTag = rExec.join("testExecutionTags").join("tag");
      Predicate pTagNameInFixedList = rTag.get("name").in(cb.parameter(List.class, "tagList"));
      Predicate pHavingAllTagsPresent = cb.ge(cb.count(rTag.get("id")), cb.parameter(Long.class, "tagListSize"));

      query.where(cb.and(pTagNameInFixedList, pTestUID));
      query.having(pHavingAllTagsPresent);
      query.groupBy(rExec.get("test"), rExec.get("id"), rExec.get("name"), rExec.get("started"), rExec.get("comment"));

      return query;
   }

   /**
    * Helper method. Search query is quite complicated and has a lot of parameters. This method
    * assigns the value to every predefined parameter.
    *
    * @param query
    * @param search
    * @param includedTags
    * @param excludedTags
    * @param userGroups
    */
   private void fillParameterValues(TypedQuery<TestExecution> query, TestExecutionSearchTO search, List<String> includedTags, List<String> excludedTags,  List<String> userGroups) {
      if (search.getStartedFrom() != null) {
         query.setParameter("startedFrom", search.getStartedFrom());
      }
      if (search.getStartedTo() != null) {
         query.setParameter("startedTo", search.getStartedTo());
      }
      if (!includedTags.isEmpty()) {
         query.setParameter("tagList", includedTags);
         query.setParameter("tagListSize", new Long(includedTags.size()));
      }
      if (!excludedTags.isEmpty()) {
         query.setParameter("excludedTagList", excludedTags);
      }
      if (search.getTestName() != null && !"".equals(search.getTestName())) {
         if (search.getTestName().endsWith("*")) {
            String pattern = search.getTestName().substring(0, search.getTestName().length() -1).concat("%").toLowerCase();
            query.setParameter("testName", pattern);
         } else {
            query.setParameter("testName", search.getTestName().toLowerCase());
         }
      }
      if (search.getTestUID() != null && !"".equals(search.getTestUID())) {
         if (search.getTestUID().endsWith("*")) {
            String pattern = search.getTestUID().substring(0, search.getTestUID().length() -1).concat("%").toLowerCase();
            query.setParameter("testUID", pattern);
         } else {
            query.setParameter("testUID", search.getTestUID().toLowerCase());
         }
      }
      if (GroupFilter.MY_GROUPS.equals(search.getGroupFilter())) {
         query.setParameter("groupNames", userGroups);
      }
      if (search.getParameters() != null && !search.getParameters().isEmpty()) {
         int pCount = 1;
         for (ParamCriteria paramCriteria : search.getParameters()) {
            query.setParameter("paramName" + pCount, paramCriteria.getName());
            query.setParameter("paramValue" + pCount, paramCriteria.getValue());
            pCount++;
         }
      }
   }

   /**
    * Helper method. Because when trying to retrieve count of test executions according to
    * some restrictions (like tags etc, in general when the query has having, where, group by together) via
    * Criteria API, there is no way to reuse the query even though it differs in two lines.
    *
    * Hence, the basics of the query are extracted to this method to avoid code duplication.
    *
    * @param criteriaQuery
    * @param testUIDs
    * @param tags
    * @return
    */
   private TypedQuery createTypedQueryByTags(CriteriaQuery criteriaQuery, List<String> testUIDs, List<String> tags) {
      TypedQuery<TestExecution> query = query(criteriaQuery);
      query.setParameter("testUID", testUIDs);
      query.setParameter("tagList", tags);
      query.setParameter("tagListSize", new Long(tags.size()));

      return query;
   }

   /**
    * Helper method. Divides the list of tags to two groups - included and excluded tags. Excluded tags have
    * prefix '-'. Divides and stores it into the parameters.
    *
    * @param inputTags
    * @param outputExcluded
    * @param outputIncluded
    * @return
    */
   private void divideTags(List<String> inputTags, List<String> outputIncluded, List<String> outputExcluded) {
      Iterator<String> iterator = inputTags.iterator();
      while (iterator.hasNext()) {
         String value = iterator.next();
         if (value.isEmpty()) {
            continue;
         }

         if (value.startsWith("-")) {
            outputExcluded.add(value.substring(1));
         }
         else {
            outputIncluded.add(value);
         }
      }
   }

   /**
    * Fetch test via JPA relationship.
    *
    * @param exec
    * @return
    */
   public static TestExecution fetchTest(TestExecution exec) {
      exec.setTest(exec.getTest().clone());
      exec.getTest().setTestExecutions(null);
      exec.getTest().setTestMetrics(null);
      return exec;
   }

   /**
    * Fetch tags via JPA relationships.
    *
    * @param testExecution
    * @return TestExecution with fetched tags
    */
   public static TestExecution fetchTags(TestExecution testExecution) {
      Collection<TestExecutionTag> cloneTags = new ArrayList<TestExecutionTag>();
      for (TestExecutionTag interObject : testExecution.getTestExecutionTags()) {
         cloneTags.add(interObject.cloneWithTag());
      }
      testExecution.setTestExecutionTags(cloneTags);
      return testExecution;
   }

   /**
    * Fetch parameters via JPA relationships.
    *
    * @param testExecution
    * @return TestExecution with fetched parameters
    */
   public static TestExecution fetchParameters(TestExecution testExecution) {
      testExecution.setParameters(EntityUtils.clone(testExecution.getParameters()));
      return testExecution;
   }

   /**
    * Fetch attachments via JPA relationships.
    *
    * @param testExecution
    * @return TestExecution with fetched attachments
    */
   public static TestExecution fetchAttachments(TestExecution testExecution) {
      testExecution.setAttachments(EntityUtils.clone(testExecution.getAttachments()));
      return testExecution;
   }

   /**
    * Fetch values with parameters via JPA relationships.
    *
    * @param testExecution
    * @return TestExecution with fetched values
    */
   public static TestExecution fetchValues(TestExecution testExecution) {
      List<Value> cloneValues = new ArrayList<Value>();
      if (testExecution.getValues() != null) {
         for (Value v : testExecution.getValues()) {
            cloneValues.add(v.cloneWithParameters());
         }
         testExecution.setValues(cloneValues);
      }
      return testExecution;
   }
}
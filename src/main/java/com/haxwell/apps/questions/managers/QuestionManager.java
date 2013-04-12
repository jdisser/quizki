package com.haxwell.apps.questions.managers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.logging.Logger;

import javax.persistence.EntityManager;
import javax.persistence.Query;

import com.haxwell.apps.questions.checkers.AbstractQuestionTypeChecker;
import com.haxwell.apps.questions.constants.Constants;
import com.haxwell.apps.questions.constants.TypeConstants;
import com.haxwell.apps.questions.entities.Question;
import com.haxwell.apps.questions.entities.Topic;
import com.haxwell.apps.questions.entities.User;
import com.haxwell.apps.questions.factories.QuestionTypeCheckerFactory;
import com.haxwell.apps.questions.utils.ListFilterer;
import com.haxwell.apps.questions.utils.PaginationData;
import com.haxwell.apps.questions.utils.ShouldRemoveAnObjectCommand;
import com.haxwell.apps.questions.utils.StringUtil;
import com.haxwell.apps.questions.utils.TypeUtil;

public class QuestionManager extends Manager {
	
	public static Logger log = Logger.getLogger(QuestionManager.class.getName());
	
	public static long persistQuestion(Question question) 
	{
		EntityManager em = emf.createEntityManager();

		em.getTransaction().begin();
		
		Question rtn = em.merge(question);
		
		em.getTransaction().commit();
		
		em.close();
		
		return rtn.getId();
	}
	
	public static void deleteQuestion(long userId, String questionId)
	{
		deleteQuestion(userId, getQuestionById(questionId));
	}
	
	public static void deleteQuestion(long userId, Question question)
	{
		EntityManager em = emf.createEntityManager();
		
		long questionId = question.getId();		
		
		em.getTransaction().begin();
		
		// Delete related REFERENCEs
		Query query = em.createNativeQuery("SELECT qr.reference_id FROM question_reference qr WHERE qr.question_id = ?1");
		query.setParameter(1, questionId);
		
		List<Long> referenceIds = (List<Long>)query.getResultList();
		
		if (!referenceIds.isEmpty()) {
			query = em.createNativeQuery("DELETE FROM question_reference WHERE question_id = ?1");
			query.setParameter(1, questionId);
			
			query.executeUpdate();
			
			String str = "DELETE FROM reference WHERE ";
			
			for (int i = 0; i < referenceIds.size(); i++)
			{
				str += "id= " + referenceIds.get(i);
				
				if (i+1 < referenceIds.size())
					str += " OR ";
			}
				
			query = em.createNativeQuery(str);
			query.executeUpdate();
		}
		
		// Delete related TOPICs
		query = em.createNativeQuery("SELECT qt.topic_id FROM question_topic qt WHERE qt.question_id = ?1");
		query.setParameter(1, questionId);
		
		List<Long> topicIds = (List<Long>)query.getResultList();

		if (!topicIds.isEmpty()) {
			query = em.createNativeQuery("DELETE FROM question_topic WHERE question_id = ?1");
			query.setParameter(1, questionId);
			query.executeUpdate();
			
			for (Long l : topicIds)
			{
				// Are there any more questions using this topic?
				query = em.createNativeQuery("SELECT qt.question_id FROM question_topic qt WHERE qt.topic_id = ?1");
				query.setParameter(1, l);
				
				List<Long> list = (List<Long>)query.getResultList();
				
				// If not, delete this topic, too..
				if (list.size() == 0) {
					query = em.createNativeQuery("DELETE FROM topic WHERE id = ?1");
					query.setParameter(1, l);
					query.executeUpdate();
				}
			}
		}
		
		// Delete related CHOICEs
		query = em.createNativeQuery("SELECT qc.choice_id FROM question_choice qc WHERE qc.question_id = ?1");
		query.setParameter(1, questionId);
		
		List<Long> choiceIds = (List<Long>)query.getResultList();
		
		if (!choiceIds.isEmpty()) {
			query = em.createNativeQuery("DELETE FROM question_choice WHERE question_id = ?1");
			query.setParameter(1, questionId);
			query.executeUpdate();
			
			String str = "DELETE FROM choice WHERE ";
			
			for (int i = 0; i < choiceIds.size(); i++)
			{
				str += "id= " + choiceIds.get(i);
				
				if (i+1 < choiceIds.size())
					str += " OR ";
			}
				
			query = em.createNativeQuery(str);
			query.executeUpdate();
		}

		// Delete related EXAMs (if necessary)
		query = em.createNativeQuery("SELECT eq.exam_id FROM exam_question eq WHERE eq.question_id = ?1");
		query.setParameter(1, questionId);

		List<Long> examIds = (List<Long>)query.getResultList();
		
		if (!examIds.isEmpty()) {
			query = em.createNativeQuery("DELETE FROM exam_question WHERE question_id = ?1");
			query.setParameter(1, questionId);
			query.executeUpdate();
			
			for (Long examId : examIds)
			{
				// Who is the owner of this exam?
				query = em.createNativeQuery("SELECT e.user_id FROM exam e where e.id = ?1");
				query.setParameter(1, examId);
				
				Long IDofTheOwnerOfThisExam = (Long)query.getSingleResult();
				
				// if its not the given user id
				if (IDofTheOwnerOfThisExam != userId) {
					// 	notify them that the question was removed from their exam
					NotificationManager.issueNotification_questionDeletedAndRemovedFromExam(IDofTheOwnerOfThisExam, questionId, examId);
				}
				
				// For the given exam ID, does it have any more questions?
				query = em.createNativeQuery("SELECT eq.question_id FROM exam_question eq WHERE eq.exam_id = ?1");
				query.setParameter(1, examId);
				
				List<Long> list = (List<Long>)query.getResultList();
				
				// If not, this exam is empty.. delete this exam ID..
				if (list.size() == 0) {
					NotificationManager.issueNotification_emptyExamWasDeleted(examId);
					
					query = em.createNativeQuery("DELETE FROM exam WHERE id = ?1");
					query.setParameter(1, examId);
					query.executeUpdate();
					
				}
			}
		}
		
		// Delete the question itself..
		query = em.createNativeQuery("DELETE FROM question WHERE id = ?1");
		query.setParameter(1, questionId);
		query.executeUpdate();
		
		em.getTransaction().commit();
		
		em.close();
	}
	
	public static Question newQuestion()
	{
		return new Question();
	}
	
	public static Collection<Question> getQuestionsByTopic(long topicId, PaginationData pd)
	{
		EntityManager em = emf.createEntityManager();
		boolean b = em.isOpen();

		Query query = em.createNativeQuery("SELECT qt.question_id FROM question_topic qt WHERE qt.topic_id = ?1");
		
		query.setParameter(1, topicId);
				
		List<Long> list = (List<Long>)query.getResultList();
		
		Collection<Question> coll = getQuestionsById(StringUtil.getCSVString(list), pd);
		
		return coll;
	}
	
	public static Collection<Question> getAllQuestions() {
		EntityManager em = emf.createEntityManager();
		
		Query query = em.createQuery("SELECT q FROM Question q");
		
		return (Collection<Question>)query.getResultList();
	}
	
	public static Collection<Question> getAllQuestions(PaginationData pd)
	{
		EntityManager em = emf.createEntityManager();
		
		Query query = em.createQuery("SELECT q FROM Question q");
		
		int pageSize = pd.getPageSize();
		int pageNumber = pd.getPageNumber();
		
		query.setMaxResults(pageSize);
		query.setFirstResult(pageNumber * pageSize);
		
		Collection<Question> rtn = query.getResultList();
		
		em.close();
		
		pd.setTotalItemCount(getNumberOfQuestionsInTotal());		
		
		return (Collection<Question>)query.getResultList();
	}

	/**
	 * Takes comma delimited list of IDs, and returns a collection of the Question 
	 * object represented by those IDs.
	 * 
	 * @param questionIDs
	 * @return
	 */
	public static Collection<Question> getQuestionsById(String questionIDs, PaginationData pd) {
		Collection<Question> rtn = new ArrayList<Question>();
		
		EntityManager em = emf.createEntityManager();
		
		StringTokenizer tokenizer = new StringTokenizer(questionIDs, ",");
		
		if (tokenizer.hasMoreTokens())
		{
			int tokenCount = tokenizer.countTokens();
			
			String queryStr = "SELECT q FROM Question q";
			String whereClause = " WHERE ";
			
			while (tokenizer.hasMoreTokens())
			{
				String token = tokenizer.nextToken();
	
				whereClause += " q.id=" + token;
				
				if (tokenizer.hasMoreTokens())
				{
					whereClause += " OR ";
				}
			}
			
			queryStr += whereClause;

			Query query = em.createQuery(queryStr);
			
			int pageSize = pd.getPageSize();
			int pageNumber = pd.getPageNumber();
			
			query.setMaxResults(pageSize);
			query.setFirstResult(pageNumber * pageSize);
			
			rtn = (Collection<Question>)query.getResultList();
			
			pd.setTotalItemCount(tokenCount);
		}
		
		return rtn;
	}
	
	public static Question getQuestionById(String aSingleId)
	{
		return getQuestionById(Long.parseLong(aSingleId));
	}
	
	public static Question getQuestionById(long aSingleId)
	{
		Question rtn = null;
		
		EntityManager em = emf.createEntityManager();
		
		String queryStr = "SELECT q FROM Question q WHERE q.id=?1";

		Query query = em.createQuery(queryStr);
		
		query.setParameter(1, aSingleId);
		
		try {
			rtn = (Question)query.getSingleResult();
		}
		catch (Exception e) 
		{
			String strr = e.getMessage();
		}
		finally {
			em.close();
		}

		return rtn;
	}
	
	public static boolean isAnsweredCorrectly(Question question, Map<String, String> answers)
	{
		AbstractQuestionTypeChecker checker = QuestionTypeCheckerFactory.getChecker(question);
		return checker.questionIsCorrect(answers);
	}

	public static long getNumberOfQuestionsInTotal() {
		EntityManager em = emf.createEntityManager();
		
		Query query = em.createNativeQuery("SELECT count(*) FROM question");
		
		Long rtn = (Long)query.getSingleResult();
		
		em.close();
		
		return rtn;
	}
	
	public static long getNumberOfQuestionsCreatedByUser(long id) {
		EntityManager em = emf.createEntityManager();
		
		Query query = em.createNativeQuery("SELECT count(*) FROM question WHERE user_id = ?1");
		
		query.setParameter(1, id);
		
		Long rtn = (Long)query.getSingleResult();
		
		em.close();
		
		return rtn;
	}

	public static Collection<Question> getAllQuestionsForUser(long id, PaginationData pd) {
		EntityManager em = emf.createEntityManager();
		
		Query query = em.createQuery("SELECT q FROM Question q, User u WHERE q.user.id = u.id AND u.id = ?1", Question.class);
		
		query.setParameter(1, id);
		
		int pageSize = pd.getPageSize();
		int pageNumber = pd.getPageNumber();
		
		query.setMaxResults(pageSize);
		query.setFirstResult(pageNumber * pageSize);
		
		Collection<Question> rtn = query.getResultList();
		
		em.close();
		
		pd.setTotalItemCount(getNumberOfQuestionsCreatedByUser(id));
		
		return rtn;
	}

	public static Collection<Question> getQuestionsThatContain(final String topicFilterText, final String filterText, final int maxDifficulty, final Integer questionType, PaginationData pd) {
		EntityManager em = emf.createEntityManager();
		
		String queryString = "SELECT q FROM Question q WHERE ";
		
		if (!StringUtil.isNullOrEmpty(filterText))
			queryString += "q.text LIKE ?2 OR q.description LIKE ?2 AND ";
		
		queryString += "q.difficulty.id <= ?1";
		
		Query query = em.createQuery(queryString, Question.class);
		
		if (!StringUtil.isNullOrEmpty(filterText))
			query.setParameter(2, "%" + filterText + "%");
		
		query.setParameter(1, maxDifficulty);
		
		List<Question> rtn = (List<Question>)query.getResultList();

		rtn = (List<Question>)filterQuestionListByTopicAndQuestionType(topicFilterText, questionType, rtn);
		
		pd.setTotalItemCount(rtn.size());
		
		List<Question> paginatedList = new ArrayList<Question>();
		
		int rtnSize = rtn.size();
		
		if (rtnSize > pd.getPageSize())
		{
			int pageSize = pd.getPageSize();
			int pageNumber = pd.getPageNumber();
			
			for (int i = pageSize * pageNumber; i < Math.min(rtnSize, ((pageSize * pageNumber) + pageSize)); i++) {
				paginatedList.add(rtn.get(i));
			}
		}
		else
			paginatedList = rtn;

		return paginatedList;
	}
	
	public static Collection<Question> getQuestionsCreatedByAGivenUserThatContain(long userId, final String topicFilterText, String filterText, Integer maxDifficulty, final Integer questionType, PaginationData pd) {
		EntityManager em = emf.createEntityManager();
		
		String queryString = "SELECT q FROM Question q, User u WHERE q.user.id = u.id AND u.id = ?1 AND ";
		
		if (!StringUtil.isNullOrEmpty(filterText))
			queryString += "((q.text LIKE ?2 AND q.description =\"\") OR q.description LIKE ?2) AND ";
		
		queryString += "q.difficulty.id <= ?3";
		
		Query query = em.createQuery(queryString, Question.class);
		
		query.setParameter(1, userId);
		
		if (!StringUtil.isNullOrEmpty(filterText))
			query.setParameter(2, "%" + filterText + "%");
		
		query.setParameter(3, maxDifficulty);
		
		//
		// Get query results
		List<Question> rtn = (List<Question>)query.getResultList();
		
		pd.setTotalItemCount(rtn.size());

		rtn = (List<Question>)filterQuestionListByTopicAndQuestionType(topicFilterText, questionType, rtn);
		
		List<Question> paginatedList = new ArrayList<Question>();
		
		int rtnSize = rtn.size();
		
		if (pd.getPageNumber() > pd.getMaxPageNumber())
			pd.setPageNumber(pd.getMaxPageNumber());
		
		if (rtnSize > pd.getPageSize())
		{
			int pageSize = pd.getPageSize();
			int pageNumber = pd.getPageNumber();
			
			for (int i = pageSize * pageNumber; i < Math.min(rtnSize, ((pageSize * pageNumber) + pageSize)); i++) {
				paginatedList.add(rtn.get(i));
			}
		}
		else
			paginatedList = rtn;
		
//		pd.setTotalItemCount(paginatedList.size());		

		return paginatedList;
	}

	private static Collection<Question> filterQuestionListByTopicAndQuestionType(
			final String topicFilterText, final Integer questionType,
			Collection<Question> rtn) {
		ArrayList<ShouldRemoveAnObjectCommand<Question>> arr = new ArrayList<ShouldRemoveAnObjectCommand<Question>>();
		
		if (!StringUtil.isNullOrEmpty(topicFilterText))
			arr.add(new TopicFilter(topicFilterText));

		if (questionType != null && questionType != TypeConstants.ALL_TYPES )
			arr.add(new QuestionTypeFilter(questionType));

		return new ListFilterer<Question>().process(rtn, arr);
	}
	
	public static List<String> validate(Question questionObj) {
		List<String> errors = new ArrayList<String>();

		errors.addAll(ChoiceManager.validate(questionObj));
		
		String questionText = questionObj.getText();
		
		if (questionObj.getTopics().size() < 1)
			errors.add("Question must have at least one topic.");
		
		if (StringUtil.isNullOrEmpty(questionText))
			errors.add("Question must have some text!");
		
		if (questionText != null && questionText.length() > Constants.MAX_QUESTION_TEXT_LENGTH)
			errors.add("Question text cannot be longer than " + Constants.MAX_QUESTION_TEXT_LENGTH + " characters. Perhaps a seperate question is in order!");
		
		if (questionObj.getDescription() != null && questionObj.getDescription().length() > Constants.MAX_QUESTION_DESCRIPTION_LENGTH)
			errors.add("Question description cannot be longer than " + Constants.MAX_QUESTION_DESCRIPTION_LENGTH + " characters.");
		
		return errors;
	}
	
	public static boolean userCanEditThisQuestion(Question q, User u)
	{
		boolean rtn = false;
		
		if (u != null)
			rtn = (q.getUser().getId() == u.getId()); // its simple now, but in the future we'll flesh this method out..
		
		return rtn;
	}
	
	public static class TopicFilter implements ShouldRemoveAnObjectCommand<Question> {
		private String topicFilterText;
		
		public TopicFilter(String filter) {
			this.topicFilterText = filter;
		}
		
		public boolean shouldRemove(Question q) {
			Set<Topic> set = q.getTopics();

			boolean rtn = true;
			boolean matchFound = false;
			
			for (Topic t : set) {
				if (!matchFound && t.getText().contains(topicFilterText))
					matchFound = true;
			}
			
			rtn = matchFound;

			return !rtn;
		}
	}
	
	public static class QuestionTypeFilter implements ShouldRemoveAnObjectCommand<Question> {
		private int filterQuestionType;
		
		public QuestionTypeFilter(int filter) {
			this.filterQuestionType = filter;
		}
		
		public boolean shouldRemove(Question q) {
			boolean rtn = false;

			if (TypeUtil.convertToInt(q.getQuestionType()) != filterQuestionType)
				rtn = true;

			return rtn;
		}
	}
}

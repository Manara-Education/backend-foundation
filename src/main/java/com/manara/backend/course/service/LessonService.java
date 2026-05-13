package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.LessonRequest;
import com.manara.backend.course.dto.LessonResponse;
import com.manara.backend.course.mapper.LessonMapper;
import com.manara.backend.course.model.CompletedLesson;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.model.Lesson;
import com.manara.backend.course.repository.CompletedLessonRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.repository.LessonRepository;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LessonService {

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final CompletedLessonRepository completedLessonRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final LessonMapper lessonMapper;
    private final YoutubeDurationService youtubeDurationService;

    private Course getCourseAndVerifyInstructor(User user, Long courseId) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        if (!course.getInstructor().getUser().getId().equals(user.getId())) {
            throw new BusinessException("error.course.notOwner");
        }
        return course;
    }

    private void recalculateCourseDuration(Course course) {
        lessonRepository.flush();
        Integer newDuration = lessonRepository.sumDurationByCourseId(course.getId());
        course.setDuration(newDuration);
        courseRepository.save(course);
    }



    @Transactional
    public LessonResponse addLesson(User user, Long courseId, LessonRequest request) {
        Course course = getCourseAndVerifyInstructor(user, courseId);
        
        if (request.getDuration() == null) {
            request.setDuration(0);
        }
        
        Lesson lesson = lessonMapper.toLesson(request, course);
        lesson = lessonRepository.save(lesson);
        
        if (request.getDuration() == 0 && request.getVideoId() != null && !request.getVideoId().isEmpty()) {
            youtubeDurationService.fetchAndUpdateDurationAsync(lesson.getId(), request.getVideoId());
        } else {
            recalculateCourseDuration(course);
        }
        
        return lessonMapper.toLessonResponse(lesson);
    }

    @Transactional
    public LessonResponse updateLesson(User user, Long courseId, Long lessonId, LessonRequest request) {
        getCourseAndVerifyInstructor(user, courseId);
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("error.lesson.notFound", lessonId.toString()));
        
        if (!lesson.getCourse().getId().equals(courseId)) {
            throw new BusinessException("error.lesson.notInCourse");
        }

        boolean shouldFetchDuration = false;
        if (request.getDuration() == null || request.getDuration() == 0 || !request.getVideoId().equals(lesson.getVideoId())) {
            request.setDuration(0);
            shouldFetchDuration = true;
        }

        lesson.setTitle(request.getTitle());
        lesson.setSummary(request.getSummary());
        lesson.setDescription(request.getDescription());
        lesson.setVideoId(request.getVideoId());
        lesson.setDuration(request.getDuration());
        lesson.setOrderIndex(request.getOrderIndex());
        
        lesson = lessonRepository.save(lesson);
        
        if (shouldFetchDuration && request.getVideoId() != null && !request.getVideoId().isEmpty()) {
            youtubeDurationService.fetchAndUpdateDurationAsync(lesson.getId(), request.getVideoId());
        } else {
            recalculateCourseDuration(lesson.getCourse());
        }
        
        return lessonMapper.toLessonResponse(lesson);
    }

    @Transactional
    public void deleteLesson(User user, Long courseId, Long lessonId) {
        getCourseAndVerifyInstructor(user, courseId);
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("error.lesson.notFound", lessonId.toString()));

        if (!lesson.getCourse().getId().equals(courseId)) {
            throw new BusinessException("error.lesson.notInCourse");
        }

        Course course = lesson.getCourse();
        lessonRepository.delete(lesson);
        recalculateCourseDuration(course);
    }

    public List<LessonResponse> getCourseLessons(User user, Long courseId) {
        courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        
        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderIndexAsc(courseId);

        Student student = null;
        if (user != null && user.getRole() == Role.STUDENT) {
            Optional<Student> studentOpt = studentRepository.findByUserId(user.getId());
            if (studentOpt.isPresent() && enrollmentRepository.findByCourseIdAndStudentId(courseId, studentOpt.get().getId()).isPresent()) {
                student = studentOpt.get();
            }
        }

        final Student finalStudent = student;
        return lessons.stream().map(lesson -> {
            boolean isCompleted = false;
            if (finalStudent != null) {
                isCompleted = completedLessonRepository.findByStudentIdAndLessonId(finalStudent.getId(), lesson.getId()).isPresent();
            }
            return lessonMapper.toLessonResponse(lesson, isCompleted);
        }).collect(Collectors.toList());
    }

    @Transactional
    public void markLessonCompleted(User user, Long courseId, Long lessonId) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        Student student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));

        Enrollment enrollment = enrollmentRepository.findByCourseIdAndStudentId(courseId, student.getId())
                .orElseThrow(() -> new BusinessException("error.course.notEnrolled"));

        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("error.lesson.notFound", lessonId.toString()));

        if (!lesson.getCourse().getId().equals(courseId)) {
            throw new BusinessException("error.lesson.notInCourse");
        }

        if (completedLessonRepository.findByStudentIdAndLessonId(student.getId(), lessonId).isEmpty()) {
            CompletedLesson completedLesson = CompletedLesson.builder()
                    .student(student)
                    .lesson(lesson)
                    .build();
            completedLessonRepository.save(completedLesson);

            // Recalculate progress
            int totalLessons = lessonRepository.countByCourseId(courseId);
            if (totalLessons > 0) {
                int completedLessonsCount = completedLessonRepository.countByStudentIdAndLesson_Course_Id(student.getId(), courseId);
                // completedLessonsCount includes the one we just saved because hibernate session holds it, 
                // but count query goes to DB. Let's do it safely by querying + 1 or flushing.
                // It's safer to just calculate mathematically if we know this is the new one.
                int progress = (int) (((double) (completedLessonsCount + 1) / totalLessons) * 100);
                enrollment.setProgress(Math.min(progress, 100));
                enrollmentRepository.save(enrollment);
            }
        }
    }
}

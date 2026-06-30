package com.manara.backend.lesson.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.lesson.dto.LessonDetailsResponse;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
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

        Lesson lesson = lessonMapper.toLesson(request, course);
        lesson = lessonRepository.save(lesson);

        youtubeDurationService.fetchAndUpdateDurationAsync(lesson.getId(), request.getVideoUrl());

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

        boolean videoUrlChanged = !request.getVideoUrl().equals(lesson.getVideoUrl());

        lesson.setTitle(request.getTitle());
        lesson.setSummary(request.getSummary());
        lesson.setDescription(request.getDescription());
        lesson.setVideoUrl(request.getVideoUrl());
        lesson.setOrderIndex(request.getOrderIndex());

        if (videoUrlChanged) {
            lesson.setDuration(0);
        }

        lesson = lessonRepository.save(lesson);

        if (videoUrlChanged) {
            youtubeDurationService.fetchAndUpdateDurationAsync(lesson.getId(), request.getVideoUrl());
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
        completedLessonRepository.deleteByLessonId(lessonId);
        lessonRepository.delete(lesson);
        recalculateCourseDuration(course);
    }

    public LessonDetailsResponse getLesson(User user, Long courseId, Long lessonId) {
        courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));

        List<Lesson> lessons = lessonRepository.findByCourseIdOrderByOrderIndexAsc(courseId);

        int index = -1;
        for (int i = 0; i < lessons.size(); i++) {
            if (lessons.get(i).getId().equals(lessonId)) {
                index = i;
                break;
            }
        }
        if (index == -1) {
            if (!lessonRepository.existsById(lessonId)) {
                throw new ResourceNotFoundException("error.lesson.notFound", lessonId.toString());
            }
            throw new BusinessException("error.lesson.notInCourse");
        }

        Lesson lesson = lessons.get(index);
        Lesson previous = index > 0 ? lessons.get(index - 1) : null;
        Lesson next = index < lessons.size() - 1 ? lessons.get(index + 1) : null;

        Boolean isCompleted = null;
        if (user != null && user.getRole() == Role.STUDENT) {
            Optional<Student> studentOpt = studentRepository.findByUserId(user.getId());
            if (studentOpt.isPresent() && enrollmentRepository.findByCourseIdAndStudentId(courseId, studentOpt.get().getId()).isPresent()) {
                isCompleted = completedLessonRepository
                        .findByStudentIdAndLessonId(studentOpt.get().getId(), lessonId)
                        .isPresent();
            }
        }

        return lessonMapper.toLessonDetailsResponse(lesson, isCompleted, previous, next);
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
            CompletedLesson completedLesson = lessonMapper.toCompletedLesson(student, lesson);
            completedLessonRepository.save(completedLesson);

            int totalLessons = lessonRepository.countByCourseId(courseId);
            if (totalLessons > 0) {
                int completedLessonsCount = completedLessonRepository.countByStudentIdAndLesson_Course_Id(student.getId(), courseId);
                int progress = (int) (((double) (completedLessonsCount + 1) / totalLessons) * 100);
                enrollment.setProgress(Math.min(progress, 100));
                enrollmentRepository.save(enrollment);
            }
        }
    }
}

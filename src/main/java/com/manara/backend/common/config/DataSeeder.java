package com.manara.backend.common.config;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final InstructorRepository instructorRepository;
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        String defaultInstructorEmail = "instructor@manara.com";
        String defaultStudentEmail = "student@manara.com";

        // Seed Instructor
        Instructor instructor = null;
        if (userRepository.findByEmail(defaultInstructorEmail).isEmpty()) {
            log.info("Seeding default instructor: {}", defaultInstructorEmail);

            User instructorUser = User.builder()
                    .fullName("محمد الأمين")
                    .email(defaultInstructorEmail)
                    .password(passwordEncoder.encode("password123"))
                    .role(Role.INSTRUCTOR)
                    .emailVerified(true)
                    .build();

            instructorUser = userRepository.save(instructorUser);

            instructor = Instructor.builder()
                    .user(instructorUser)
                    .bio("أستاذ متخصص في اللغة العربية وعلوم النحو والصرف لأكثر من 15 عاماً.")
                    .specialization("النحو والصرف")
                    .build();

            instructor = instructorRepository.save(instructor);
            log.info("Default instructor seeded successfully.");
        } else {
            instructor = instructorRepository.findByUserId(
                    userRepository.findByEmail(defaultInstructorEmail).get().getId()
            ).orElse(null);
            log.info("Default instructor already exists. Skipping seed.");
        }

        // Seed Student (un-enrolled by default)
        if (userRepository.findByEmail(defaultStudentEmail).isEmpty()) {
            log.info("Seeding default student: {}", defaultStudentEmail);

            User studentUser = User.builder()
                    .fullName("أحمد طارق")
                    .email(defaultStudentEmail)
                    .password(passwordEncoder.encode("password123"))
                    .role(Role.STUDENT)
                    .emailVerified(true)
                    .build();

            studentUser = userRepository.save(studentUser);

            Student student = Student.builder()
                    .user(studentUser)
                    .build();

            studentRepository.save(student);
            log.info("Default student seeded successfully.");
        } else {
            log.info("Default student already exists. Skipping seed.");
        }

        // Seed Courses & Lessons if no courses exist
        if (courseRepository.count() == 0 && instructor != null) {
            log.info("Seeding sample courses and lessons...");

            // 1. Paid Course
            Course paidCourse = Course.builder()
                    .title("أساسيات النحو العربي")
                    .subtitle("قواعد وتطبيقات شاملة")
                    .description("رحلة شاملة في قواعد النحو العربي من المبادئ الأولى حتى الاستخدام الاحترافي في الكتابة والخطابة.")
                    .duration(1200) // 20 hours
                    .price(new BigDecimal("49.99"))
                    .image("https://images.unsplash.com/photo-1771909752761-d26abe4e60ba?crop=entropy&cs=tinysrgb&fit=max&fm=jpg&q=80&w=1080")
                    .instructor(instructor)
                    .studentsCount(0)
                    .build();

            paidCourse = courseRepository.save(paidCourse);

            Lesson lesson1 = Lesson.builder()
                    .title("مقدمة في علم النحو")
                    .summary("فهم تاريخ النحو وأهميته في صون اللسان العربي.")
                    .description("في هذا الدرس، سنتناول نشأة علم النحو وتطوره، وأهم المدارس النحوية.")
                    .videoUrl("https://www.youtube.com/watch?v=Jc__iOQgQNM")
                    .duration(2700)
                    .orderIndex(1)
                    .course(paidCourse)
                    .build();

            Lesson lesson2 = Lesson.builder()
                    .title("أقسام الكلمة: الاسم والفعل والحرف")
                    .summary("التمييز بين أنواع الكلمات وعلامات كل قسم.")
                    .description("سنتعرف على علامات الاسم، وعلامات الفعل، والحروف ومعانيها بالتفصيل.")
                    .videoUrl("https://www.youtube.com/watch?v=NYAaOAASuZQ")
                    .duration(3120)
                    .orderIndex(2)
                    .course(paidCourse)
                    .build();

            lessonRepository.save(lesson1);
            lessonRepository.save(lesson2);

            // 2. Free Course
            Course freeCourse = Course.builder()
                    .title("مهارات الكتابة الإبداعية")
                    .subtitle("من الفكرة إلى النص الإبداعي")
                    .description("دورة عملية تأخذك خطوة بخطوة نحو بناء صوتك الأدبي الخاص وصقل مهاراتك التحريرية.")
                    .duration(720) // 12 hours
                    .price(BigDecimal.ZERO)
                    .image("https://images.unsplash.com/photo-1622137879013-beaca5144a4b?crop=entropy&cs=tinysrgb&fit=max&fm=jpg&q=80&w=1080")
                    .instructor(instructor)
                    .studentsCount(0)
                    .build();

            freeCourse = courseRepository.save(freeCourse);

            Lesson lesson3 = Lesson.builder()
                    .title("من أين تبدأ الفكرة؟")
                    .summary("تقنيات العصف الذهني واصطياد الأفكار الملهمة.")
                    .description("سنتحدث عن مصادر الإلهام وكيفية تحويل الفكرة العابرة إلى قصة متماسكة.")
                    .videoUrl("https://www.youtube.com/watch?v=MqKrXi3BxTI")
                    .duration(2400)
                    .orderIndex(1)
                    .course(freeCourse)
                    .build();

            Lesson lesson4 = Lesson.builder()
                    .title("رسم الشخصيات وبناء الحبكة")
                    .summary("كيف تخلق شخصيات حية وتصنع صراعاً درامياً شيقاً.")
                    .description("سنتعلم الأبعاد الثلاثة للشخصية، وأنواع الحبكات الروائية المختلفة.")
                    .videoUrl("https://www.youtube.com/watch?v=VfL9qj95r5k")
                    .duration(3300)
                    .orderIndex(2)
                    .course(freeCourse)
                    .build();

            lessonRepository.save(lesson3);
            lessonRepository.save(lesson4);

            log.info("Sample courses and lessons seeded successfully.");
        }
    }
}

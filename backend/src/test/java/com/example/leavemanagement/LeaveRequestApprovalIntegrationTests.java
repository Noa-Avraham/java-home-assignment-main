package com.example.leavemanagement;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.example.leavemanagement.service.LeaveRequestService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LeaveRequestApprovalIntegrationTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private LeaveRequestRepository requests;

    @Autowired
    private LeaveRequestService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void approve_PendingVacation_PersistsApprovedStatus() throws Exception {
        Employee employee = employee();
        saveRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED, "2026-01-01", "2026-01-18");
        LeaveRequest pending = saveRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                "2026-04-01", "2026-04-02");

        mvc.perform(post("/api/leave-requests/{id}/approve", pending.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pending.getId()))
                .andExpect(jsonPath("$.status").value(1))
                .andExpect(jsonPath("$.days").value(2));

        assertEquals(LeaveStatus.APPROVED, requests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_MissingRequest_Returns404() throws Exception {
        mvc.perform(post("/api/leave-requests/{id}/approve", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().string("Leave request not found"));
    }

    @ParameterizedTest
    @EnumSource(value = LeaveStatus.class, names = {"APPROVED", "REJECTED"})
    void approve_ProcessedRequest_Returns409WithoutChangingStatus(LeaveStatus existingStatus) throws Exception {
        Employee employee = employee();
        LeaveRequest processed = saveRequest(employee, LeaveType.VACATION, existingStatus,
                "2026-04-01", "2026-04-02");

        mvc.perform(post("/api/leave-requests/{id}/approve", processed.getId()))
                .andExpect(status().isConflict())
                .andExpect(content().string("Only pending requests can be approved"));

        assertEquals(existingStatus, requests.findById(processed.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_BalanceConsumedAfterCreation_Returns409AndRemainsPending() throws Exception {
        Employee employee = employee();
        saveRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED, "2026-01-01", "2026-01-18");
        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(employee.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 4, 1));
        dto.setEndDate(LocalDate.of(2026, 4, 2));
        LeaveRequest pending = service.create(dto);
        saveRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED, "2026-02-01", "2026-02-01");

        mvc.perform(post("/api/leave-requests/{id}/approve", pending.getId()))
                .andExpect(status().isConflict())
                .andExpect(content().string("Not enough vacation balance"));

        assertEquals(LeaveStatus.PENDING, requests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_CrossYearVacation_ChecksBothYearlyBalances() throws Exception {
        Employee employee = employee();
        saveRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED, "2026-01-01", "2026-01-17");
        saveRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED, "2027-02-01", "2027-02-18");
        LeaveRequest pending = saveRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                "2026-12-29", "2027-01-03");

        mvc.perform(post("/api/leave-requests/{id}/approve", pending.getId()))
                .andExpect(status().isConflict())
                .andExpect(content().string("Not enough vacation balance"));

        assertEquals(LeaveStatus.PENDING, requests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void approve_ConcurrentVacationsForSameEmployee_OnlyOneFitsQuota() throws Exception {
        Employee employee = employee();
        saveRequest(employee, LeaveType.VACATION, LeaveStatus.APPROVED, "2026-01-01", "2026-01-18");
        LeaveRequest first = saveRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                "2026-04-01", "2026-04-02");
        LeaveRequest second = saveRequest(employee, LeaveType.VACATION, LeaveStatus.PENDING,
                "2026-05-01", "2026-05-02");

        List<Integer> outcomes = approveConcurrently(employee, first.getId(), second.getId());

        assertEquals(List.of(200, 409), outcomes.stream().sorted().toList());
        List<LeaveRequest> approved = requests.findByEmployeeIdAndTypeAndStatus(
                employee.getId(), LeaveType.VACATION, LeaveStatus.APPROVED);
        assertEquals(20, approved.stream().mapToInt(LeaveRequest::getDays).sum());
        assertEquals(1, requests.findByEmployeeIdAndTypeAndStatus(
                employee.getId(), LeaveType.VACATION, LeaveStatus.PENDING).size());
    }

    @Test
    void approve_SameRequestConcurrently_OnlyOneApprovalSucceeds() throws Exception {
        Employee employee = employee();
        // Sick leave bypasses quota checks, exposing an incorrectly cached PENDING status.
        LeaveRequest pending = saveRequest(employee, LeaveType.SICK, LeaveStatus.PENDING,
                "2026-04-01", "2026-04-02");

        List<Integer> outcomes = approveConcurrently(employee, pending.getId(), pending.getId());

        assertEquals(List.of(200, 409), outcomes.stream().sorted().toList());
        assertEquals(LeaveStatus.APPROVED, requests.findById(pending.getId()).orElseThrow().getStatus());
    }

    private List<Integer> approveConcurrently(Employee employee, Long firstId, Long secondId) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Integer>> attempts = new ArrayList<>();
        try {
            TransactionTemplate holder = new TransactionTemplate(transactionManager);
            holder.executeWithoutResult(transaction -> {
                employees.findByIdForUpdate(employee.getId()).orElseThrow();
                attempts.add(executor.submit(() -> approvalStatus(firstId)));
                attempts.add(executor.submit(() -> approvalStatus(secondId)));

                // Both requests must really wait on PostgreSQL locks before the holder commits.
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(25))
                        .until(() -> {
                            jdbc.execute("select pg_stat_clear_snapshot()");
                            Integer waiting = jdbc.queryForObject("""
                                    select count(*)
                                    from pg_stat_activity
                                    where datname = current_database()
                                      and state = 'active'
                                      and wait_event_type = 'Lock'
                                      and query like '%employees%'
                                    """, Integer.class);
                            return waiting != null && waiting >= 2;
                        });
            });

            return List.of(attempts.get(0).get(15, TimeUnit.SECONDS), attempts.get(1).get(15, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private int approvalStatus(Long requestId) throws Exception {
        return mvc.perform(post("/api/leave-requests/{id}/approve", requestId))
                .andReturn().getResponse().getStatus();
    }

    private Employee employee() {
        Employee employee = new Employee();
        employee.setName("Approval Test Employee");
        employee.setAnnualQuota(20);
        return employees.save(employee);
    }

    private LeaveRequest saveRequest(Employee employee, LeaveType type, LeaveStatus status,
                                    String startDate, String endDate) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(employee.getId());
        request.setType(type);
        request.setStatus(status);
        request.setStartDate(LocalDate.parse(startDate));
        request.setEndDate(LocalDate.parse(endDate));
        request.setDays(Math.toIntExact(ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1));
        return requests.save(request);
    }
}

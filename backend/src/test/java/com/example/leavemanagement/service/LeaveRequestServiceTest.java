package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.exception.InvalidLeaveRequestException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaveRequestServiceTest {

    @Mock
    private EmployeeRepository employees;

    @Mock
    private LeaveRequestRepository leaveRequests;

    @InjectMocks
    private LeaveRequestService service;

    private Employee employee;

    @BeforeEach
    void setUp() {
        employee = new Employee();
        employee.setId(1L);
        employee.setAnnualQuota(20);

        when(employees.findById(1L))
                .thenReturn(Optional.of(employee));
    }

    @Test
    void create_WhenRequestExceedsRemainingBalance_ThrowsException() {
        givenApprovedVacations(approvedVacation("2026-01-06", "2026-01-23"));
        CreateLeaveRequestDto dto = vacationRequest(3);

        InvalidLeaveRequestException exception = assertThrows(
                InvalidLeaveRequestException.class,
                () -> service.create(dto));

        assertEquals(
                "Not enough vacation balance",
                exception.getMessage());

        verify(leaveRequests, never()).save(any(LeaveRequest.class));
    }

    @Test
    void create_WhenRequestExactlyMatchesRemainingBalance_Succeeds() {
        givenApprovedVacations(approvedVacation("2026-01-06", "2026-01-23"));
        when(leaveRequests.save(any(LeaveRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LeaveRequest result = service.create(vacationRequest(2));

        assertEquals(2, result.getDays());
        assertEquals(LeaveStatus.PENDING, result.getStatus());
        verify(leaveRequests).save(result);
    }

    @Test
    void create_ApprovedVacationsInOtherYears_DoNotReduceBalance() {
        givenApprovedVacations(
                approvedVacation("2025-01-01", "2025-01-18"),
                approvedVacation("2027-01-01", "2027-01-18"));

        assertCreateOutcome(vacationRequest(20), true);
    }

    @ParameterizedTest
    @CsvSource({
            "2025-04-01, 8, true",
            "2025-04-01, 9, false",
            "2026-04-01, 18, true",
            "2026-04-01, 19, false"
    })
    void create_ApprovedCrossYearVacation_CountsOnlyDaysInRequestedYear(
            LocalDate startDate, int days, boolean allowed) {

        givenApprovedVacations(approvedVacation("2025-12-20", "2026-01-02"));

        assertCreateOutcome(vacationRequest(startDate, days), allowed);
    }

    @ParameterizedTest
    @CsvSource({
            "17, 17, true",
            "18, 17, false",
            "17, 18, false"
    })
    void create_NewCrossYearVacation_ChecksBalanceInBothYears(
            int usedInFirstYear, int usedInSecondYear, boolean allowed) {

        LocalDate firstYearStart = LocalDate.of(2026, 1, 1);
        LocalDate secondYearStart = LocalDate.of(2027, 2, 1);
        givenApprovedVacations(
                approvedVacation(firstYearStart, firstYearStart.plusDays(usedInFirstYear - 1)),
                approvedVacation(secondYearStart, secondYearStart.plusDays(usedInSecondYear - 1)));

        // Three days in each year; the stored request still covers six days.
        assertCreateOutcome(vacationRequest(LocalDate.of(2026, 12, 29), 6), allowed);
    }

    @Test
    void create_MultiYearVacation_ChecksTheEntireIntermediateYear() {
        givenApprovedVacations();

        assertCreateOutcome(vacationRequest(LocalDate.of(2025, 12, 31), 367), false);
    }

    @ParameterizedTest
    @CsvSource({"17, true", "18, false"})
    void create_LeapYear_CountsFebruary29(int requestedDays, boolean allowed) {
        givenApprovedVacations(approvedVacation("2024-02-28", "2024-03-01"));

        assertCreateOutcome(vacationRequest(LocalDate.of(2024, 4, 1), requestedDays), allowed);
    }

    @ParameterizedTest
    @EnumSource(value = LeaveType.class, names = {"SICK", "UNPAID"})
    void create_NonVacationLeave_DoesNotUseVacationQuota(LeaveType type) {
        CreateLeaveRequestDto dto = vacationRequest(30);
        dto.setType(type);

        assertCreateOutcome(dto, true);

        verify(leaveRequests, never()).findByEmployeeIdAndTypeAndStatus(
                1L, LeaveType.VACATION, LeaveStatus.APPROVED);
    }

    private void givenApprovedVacations(LeaveRequest... requests) {
        when(leaveRequests.findByEmployeeIdAndTypeAndStatus(
                1L, LeaveType.VACATION, LeaveStatus.APPROVED))
                .thenReturn(List.of(requests));
    }

    private LeaveRequest approvedVacation(String startDate, String endDate) {
        return approvedVacation(LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    private LeaveRequest approvedVacation(LocalDate startDate, LocalDate endDate) {
        LeaveRequest approved = new LeaveRequest();
        approved.setEmployeeId(1L);
        approved.setType(LeaveType.VACATION);
        approved.setStatus(LeaveStatus.APPROVED);
        approved.setStartDate(startDate);
        approved.setEndDate(endDate);
        approved.setDays(Math.toIntExact(ChronoUnit.DAYS.between(startDate, endDate) + 1));
        return approved;
    }

    private void assertCreateOutcome(CreateLeaveRequestDto dto, boolean allowed) {
        if (allowed) {
            when(leaveRequests.save(any(LeaveRequest.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            LeaveRequest result = service.create(dto);

            assertEquals(ChronoUnit.DAYS.between(dto.getStartDate(), dto.getEndDate()) + 1,
                    result.getDays());
            assertEquals(LeaveStatus.PENDING, result.getStatus());
            verify(leaveRequests).save(result);
        } else {
            InvalidLeaveRequestException exception = assertThrows(
                    InvalidLeaveRequestException.class,
                    () -> service.create(dto));

            assertEquals("Not enough vacation balance", exception.getMessage());
            verify(leaveRequests, never()).save(any(LeaveRequest.class));
        }
    }

    private CreateLeaveRequestDto vacationRequest(int days) {
        return vacationRequest(LocalDate.of(2026, 4, 1), days);
    }

    private CreateLeaveRequestDto vacationRequest(LocalDate startDate, int days) {
        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(1L);
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(startDate);
        dto.setEndDate(dto.getStartDate().plusDays(days - 1));
        return dto;
    }
}

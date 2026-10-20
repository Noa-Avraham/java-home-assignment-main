package com.example.leavemanagement.service;

import com.example.leavemanagement.exception.LeaveRequestConflictException;
import com.example.leavemanagement.exception.ResourceNotFoundException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeaveRequestApprovalServiceTest {

    @Mock
    private EmployeeRepository employees;

    @Mock
    private LeaveRequestRepository leaveRequests;

    @InjectMocks
    private LeaveRequestService service;

    @Test
    void approve_PendingVacation_LocksEmployeeBeforeLoadingAndUpdatingRequest() {
        LeaveRequest request = givenRequest(LeaveType.VACATION, LeaveStatus.PENDING);
        givenApprovedVacations(vacation("2026-01-06", "2026-01-23"));
        givenSaveReturnsRequest();

        LeaveRequest result = service.approve(10L);

        assertSame(request, result);
        assertEquals(LeaveStatus.APPROVED, result.getStatus());
        InOrder order = inOrder(leaveRequests, employees);
        order.verify(leaveRequests).findEmployeeIdByRequestId(10L);
        order.verify(employees).findByIdForUpdate(1L);
        order.verify(leaveRequests).findById(10L);
        order.verify(leaveRequests).findByEmployeeIdAndTypeAndStatus(
                1L, LeaveType.VACATION, LeaveStatus.APPROVED);
        order.verify(leaveRequests).save(request);
    }

    @Test
    void approve_MissingRequest_ThrowsNotFoundWithoutLockingEmployee() {
        when(leaveRequests.findEmployeeIdByRequestId(10L)).thenReturn(Optional.empty());

        ResourceNotFoundException exception = assertThrows(
                ResourceNotFoundException.class, () -> service.approve(10L));

        assertEquals("Leave request not found", exception.getMessage());
        verifyNoInteractions(employees);
        verify(leaveRequests, never()).save(any(LeaveRequest.class));
    }

    @ParameterizedTest
    @EnumSource(value = LeaveStatus.class, names = {"APPROVED", "REJECTED"})
    void approve_AlreadyProcessedRequest_ThrowsConflict(LeaveStatus status) {
        LeaveRequest request = givenRequest(LeaveType.VACATION, status);

        LeaveRequestConflictException exception = assertThrows(
                LeaveRequestConflictException.class, () -> service.approve(10L));

        assertEquals("Only pending requests can be approved", exception.getMessage());
        assertEquals(status, request.getStatus());
        verify(leaveRequests, never()).save(any(LeaveRequest.class));
        verify(leaveRequests, never()).findByEmployeeIdAndTypeAndStatus(
                1L, LeaveType.VACATION, LeaveStatus.APPROVED);
    }

    @Test
    void approve_InsufficientCurrentBalance_LeavesRequestPending() {
        LeaveRequest request = givenRequest(LeaveType.VACATION, LeaveStatus.PENDING);
        givenApprovedVacations(vacation("2026-01-01", "2026-01-19"));

        LeaveRequestConflictException exception = assertThrows(
                LeaveRequestConflictException.class, () -> service.approve(10L));

        assertEquals("Not enough vacation balance", exception.getMessage());
        assertEquals(LeaveStatus.PENDING, request.getStatus());
        verify(leaveRequests, never()).save(any(LeaveRequest.class));
    }

    @ParameterizedTest
    @EnumSource(value = LeaveType.class, names = {"SICK", "UNPAID"})
    void approve_NonVacationLeave_DoesNotUseVacationQuota(LeaveType type) {
        LeaveRequest request = givenRequest(type, LeaveStatus.PENDING);
        givenSaveReturnsRequest();

        LeaveRequest result = service.approve(10L);

        assertEquals(LeaveStatus.APPROVED, result.getStatus());
        verify(leaveRequests).save(request);
        verify(leaveRequests, never()).findByEmployeeIdAndTypeAndStatus(
                1L, LeaveType.VACATION, LeaveStatus.APPROVED);
    }

    @ParameterizedTest
    @CsvSource({"17, true", "18, false"})
    void approve_CrossYearVacation_RechecksTheSecondYear(int usedInSecondYear, boolean allowed) {
        LeaveRequest request = givenRequest(LeaveType.VACATION, LeaveStatus.PENDING);
        request.setStartDate(LocalDate.of(2026, 12, 29));
        request.setEndDate(LocalDate.of(2027, 1, 3));
        request.setDays(6);
        givenApprovedVacations(
                vacation("2026-01-01", "2026-01-17"),
                vacation(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, usedInSecondYear)));

        if (allowed) {
            givenSaveReturnsRequest();
            LeaveRequest result = service.approve(10L);
            assertEquals(LeaveStatus.APPROVED, result.getStatus());
            assertEquals(6, result.getDays());
            verify(leaveRequests).save(request);
        } else {
            assertThrows(LeaveRequestConflictException.class, () -> service.approve(10L));
            assertEquals(LeaveStatus.PENDING, request.getStatus());
            verify(leaveRequests, never()).save(any(LeaveRequest.class));
        }
    }

    @Test
    void approve_SecondApprovalOfSameRequest_IsRejected() {
        LeaveRequest request = givenRequest(LeaveType.SICK, LeaveStatus.PENDING);
        givenSaveReturnsRequest();

        service.approve(10L);
        assertThrows(LeaveRequestConflictException.class, () -> service.approve(10L));

        assertEquals(LeaveStatus.APPROVED, request.getStatus());
        verify(leaveRequests, times(1)).save(request);
    }

    private LeaveRequest givenRequest(LeaveType type, LeaveStatus status) {
        Employee employee = new Employee();
        employee.setId(1L);
        employee.setAnnualQuota(20);
        LeaveRequest request = vacation("2026-04-01", "2026-04-02");
        request.setId(10L);
        request.setType(type);
        request.setStatus(status);

        when(leaveRequests.findEmployeeIdByRequestId(10L)).thenReturn(Optional.of(1L));
        when(employees.findByIdForUpdate(1L)).thenReturn(Optional.of(employee));
        when(leaveRequests.findById(10L)).thenReturn(Optional.of(request));
        return request;
    }

    private void givenApprovedVacations(LeaveRequest... requests) {
        when(leaveRequests.findByEmployeeIdAndTypeAndStatus(
                1L, LeaveType.VACATION, LeaveStatus.APPROVED))
                .thenReturn(List.of(requests));
    }

    private void givenSaveReturnsRequest() {
        when(leaveRequests.save(any(LeaveRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private LeaveRequest vacation(String startDate, String endDate) {
        return vacation(LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    private LeaveRequest vacation(LocalDate startDate, LocalDate endDate) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(1L);
        request.setType(LeaveType.VACATION);
        request.setStatus(LeaveStatus.APPROVED);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setDays(Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1));
        return request;
    }
}

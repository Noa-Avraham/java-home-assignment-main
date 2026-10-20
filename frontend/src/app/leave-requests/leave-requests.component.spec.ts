import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { LeaveRequest, LeaveStatus, LeaveType } from '../models/leave-request.model';
import { LeaveRequestsComponent } from './leave-requests.component';

describe('LeaveRequestsComponent', () => {
  let fixture: ComponentFixture<LeaveRequestsComponent>;
  let http: HttpTestingController;
  const apiUrl = '/api/leave-requests';
  const request: LeaveRequest = {
    id: 7,
    employeeId: 1,
    employee: { id: 1, name: 'Dana', annualQuota: 20 },
    type: LeaveType.Vacation,
    startDate: '2026-10-20',
    endDate: '2026-10-21',
    status: LeaveStatus.Pending,
    days: 2
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LeaveRequestsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(LeaveRequestsComponent);
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
    http.verify();
  });

  it('renders the API response and ends loading', () => {
    const element: HTMLElement = fixture.nativeElement;
    expect(element.textContent).toContain('Loading...');

    http.expectOne(apiUrl).flush([request]);
    fixture.detectChanges();

    expect(element.textContent).not.toContain('Loading...');
    const row = element.querySelector('tbody tr');
    expect(row?.textContent).toContain('Dana');
    expect(row?.textContent).toContain('Vacation');
    expect(row?.textContent).toContain('Pending');
  });

  it('ends loading after a failed request and allows a successful retry', () => {
    http.expectOne(apiUrl).flush('Unavailable', {
      status: 503,
      statusText: 'Service Unavailable'
    });
    fixture.detectChanges();

    const element: HTMLElement = fixture.nativeElement;
    const alert = element.querySelector('[role="alert"]');
    expect(alert?.textContent).toContain('Could not load leave requests.');
    expect(element.textContent).not.toContain('Loading...');

    const retryButton = alert?.querySelector('button');
    expect(retryButton).not.toBeNull();
    retryButton?.click();
    fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')).toBeNull();
    expect(element.textContent).toContain('Loading...');

    http.expectOne(apiUrl).flush([request]);
    fixture.detectChanges();
    expect(element.querySelector('tbody tr')?.textContent).toContain('Dana');
    expect(element.textContent).not.toContain('Loading...');
  });

  it('cancels a pending list request when the component is destroyed', () => {
    const pending = http.expectOne(apiUrl);

    fixture.destroy();

    expect(pending.cancelled).toBeTrue();
  });

  it('cancels a pending approval when the component is destroyed', () => {
    http.expectOne(apiUrl).flush([request]);
    fixture.componentInstance.approve(request.id);
    const pending = http.expectOne(`${apiUrl}/${request.id}/approve`);
    expect(pending.request.method).toBe('POST');

    fixture.destroy();

    expect(pending.cancelled).toBeTrue();
    http.expectNone(apiUrl);
  });
});

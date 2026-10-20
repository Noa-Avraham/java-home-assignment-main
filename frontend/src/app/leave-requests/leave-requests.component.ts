import { Component, DestroyRef, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { LeaveRequest, LeaveStatus, LeaveType } from '../models/leave-request.model';
import { LeaveRequestsService } from '../services/leave-requests.service';

@Component({
  selector: 'app-leave-requests',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './leave-requests.component.html',
  styleUrls: ['./leave-requests.component.css']
})
export class LeaveRequestsComponent implements OnInit {
  private readonly service = inject(LeaveRequestsService);
  private readonly destroyRef = inject(DestroyRef);

  requests: LeaveRequest[] = [];
  loading = false;
  loadError = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = '';

    this.service.getAll()
      .pipe(
        finalize(() => {
          this.loading = false;
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: requests => {
          this.requests = requests;
        },
        error: () => {
          this.loadError = 'Could not load leave requests. Please try again.';
        }
      });
  }

  // Wired up by the candidate as part of the assignment.
  approve(id: number): void {
    // TODO (candidate): call POST /api/leave-requests/{id}/approve
    // and handle loading / error / success without a generic alert.
    this.service.approve(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.load();
      });
  }

  typeLabel(type: LeaveType): string {
    if (type === LeaveType.Vacation) return 'Vacation';
    if (type === LeaveType.Sick) return 'Sick';
    return 'Unpaid';
  }

  statusLabel(status: LeaveStatus): string {
    if (status === LeaveStatus.Pending) return 'Pending';
    if (status === LeaveStatus.Approved) return 'Approved';
    return 'Rejected';
  }
}

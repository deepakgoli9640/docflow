package io.docflow.entity;

import io.docflow.constant.JobPriority;
import io.docflow.constant.JobStatus;
import io.docflow.constant.JobType;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.UUID;


@Entity
@Data
@Table(name="job_details")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name="uuid",nullable = false)
    private UUID uuid;

    @NotNull
    @Column(name="file_name",nullable = false,length = 1028)
    private String fileName;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name="job_status",nullable = false)
    private JobStatus status;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name="job_priority",nullable=false)
    private JobPriority priority;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name="job_type",nullable = false)
    private JobType type;

    @NotNull
    @Column(name ="retry_count",nullable = false)
    private int retryCount;

    @NotNull
    @Column(name="created_at",nullable = false)
    private LocalDateTime createdAt;

    @NotNull
    @Column(name="updated_at",nullable = false)
    private  LocalDateTime updatedAt;

}

package io.docflow.controller;


import io.docflow.entity.Job;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/jobs")
public class JobController {

    @GetMapping
     public ResponseEntity<String> getJobs()
    {
        return ResponseEntity.ok("Not Implemented yet");
    }

    @PostMapping
    public ResponseEntity<String> Jobs()
    {
        return  ResponseEntity.ok("Not Implemented yet");
    }

    @GetMapping("/{id}")
    public  ResponseEntity<String> getJobById()
    {
        return ResponseEntity.ok("Not Implemented yet");
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteJob()
    {
        return ResponseEntity.ok("Not Implemented yet");
    }


}

package com.finora.activation;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.UUID;

/** One {@code activation_answers} row (V276): a user's answer, or skip, at one moment on one day. */
@Entity
@Table(name = "activation_answers")
public class ActivationAnswerEntry {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "moment", nullable = false, length = 16)
    private ActivationMoment moment;

    @Enumerated(EnumType.STRING)
    @Column(name = "answer", nullable = false, length = 32)
    private ActivationAnswer answer;

    @Column(name = "answer_date", nullable = false)
    private LocalDate answerDate;

    protected ActivationAnswerEntry() {}

    public ActivationAnswerEntry(UUID userId, ActivationMoment moment, ActivationAnswer answer, LocalDate answerDate) {
        this.userId = userId;
        this.moment = moment;
        this.answer = answer;
        this.answerDate = answerDate;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public ActivationMoment getMoment() { return moment; }
    public ActivationAnswer getAnswer() { return answer; }
    public LocalDate getAnswerDate() { return answerDate; }
}

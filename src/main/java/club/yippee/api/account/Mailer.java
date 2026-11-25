package club.yippee.api.account;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import club.yippee.api.common.AppProperties;

/** The one place mail leaves from. Tests replace this bean and read what would have been sent. */
@Component
public class Mailer {

    private final JavaMailSender sender;
    private final String from;

    Mailer(JavaMailSender sender, AppProperties properties) {
        this.sender = sender;
        this.from = properties.fromEmail();
    }

    /** Sends plain text, with an HTML alternative when one is given. Throws when delivery fails. */
    public void send(String to, String subject, String text, String html) throws MessagingException {
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, html != null, "UTF-8");
        helper.setFrom(from);
        helper.setTo(to);
        helper.setSubject(subject);
        if (html == null) {
            helper.setText(text);
        } else {
            helper.setText(text, html);
        }
        sender.send(message);
    }
}

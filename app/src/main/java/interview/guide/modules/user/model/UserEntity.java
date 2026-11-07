package interview.guide.modules.user.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class UserEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** 系统随机分配的登录标识（8 位，大写字母+数字，剔除 0/O/1/I） */
  @Column(name = "userid", nullable = false, unique = true, length = 32)
  private String userid;

  /** 可空显示名，空则前端显示"无名用户" */
  @Column(name = "username", length = 64)
  private String username;

  /** BCrypt 哈希，永不存明文 */
  @Column(name = "password", nullable = false, length = 100)
  private String password;

  /** 1 启用 / 0 禁用 */
  @Column(name = "status", nullable = false)
  private Integer status;

  @Column(name = "createtime", nullable = false)
  private LocalDateTime createtime;

  @Column(name = "updatetime", nullable = false)
  private LocalDateTime updatetime;

  @PrePersist
  void prePersist() {
    this.createtime = LocalDateTime.now();
    this.updatetime = this.createtime;
  }

  @PreUpdate
  void preUpdate() {
    this.updatetime = LocalDateTime.now();
  }
}

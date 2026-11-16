package com.example.moneybug.repository;

import com.example.moneybug.entity.Payroll;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PayrollRepository extends JpaRepository<Payroll, Integer> {

    List<Payroll> findByStoreWorkerStoreIdAndPayrollMonth(Integer storeId, String payrollMonth);

    /** 확정(저장)된 월별 총액 합계. beforeMonth 는 "yyyy-MM" 문자열 (그 달은 제외). 행: [월, 합계] */
    @Query("select p.payrollMonth, sum(p.grossPay) from Payroll p "
            + "where p.storeWorker.store.id = :storeId and p.payrollMonth < :beforeMonth "
            + "group by p.payrollMonth order by p.payrollMonth")
    List<Object[]> sumGrossByMonthBefore(@Param("storeId") Integer storeId,
                                         @Param("beforeMonth") String beforeMonth);
}

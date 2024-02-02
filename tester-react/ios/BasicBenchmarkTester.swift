//
//  BasicBenchmarkTester.swift
//  batcaveTester
//
//  Created by Vithik Shah on 15/02/23.
//

import Foundation
import batcave

@objc class BasicBenchmarkTester: NSObject {
  
  @objc func tester() {
    let driver = DarwinSqliteDriverKt.darwinSqliteDriver()
    let iterations = 1000
    let batchSize = 10
    let leResponseSize = 1400
    let benchmark = BasicBenchmark(driver: driver, benchmarkTestCases: KMMBasicBenchmarkTestCases.shared, iterations: Int32(iterations), batchSize: Int32(batchSize), leResponseSize: Int32(leResponseSize))
    let writeTime = benchmark.writeAll()
    let readTime = benchmark.readAll()
    let updateTime = benchmark.update()
    let updateBatch = benchmark.updateBatch()
    let writeBatch = benchmark.writeBatch()
    print("Table Size: \(iterations) and batch Size: \(batchSize) and response Size: \(leResponseSize) kB")
    print("Time to Write All rows sequentially:  \(writeTime)")
    print("Time to Read All rows sequentially: \(readTime)")
    print("Time to Update All rows sequentially: \(updateTime)")
    print("Time to Update All rows in batches: \(updateBatch)")
    print("Time to Write All rows in batches: \(writeBatch)")
  }
}
